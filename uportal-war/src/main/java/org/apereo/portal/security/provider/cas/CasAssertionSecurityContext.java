/**
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apereo.portal.security.provider.cas;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

import org.jasig.cas.client.util.AssertionHolder;
import org.jasig.cas.client.validation.Assertion;
import org.apereo.portal.security.PortalSecurityException;
import org.apereo.portal.security.provider.ChainingSecurityContext;
import org.apereo.portal.spring.locator.ApplicationContextLocator;
import org.jasig.services.persondir.support.IAdditionalDescriptors;
import org.springframework.context.ApplicationContext;

import javax.crypto.Cipher;

/**
 * Implementation of the {@link org.apereo.portal.security.provider.cas.ICasSecurityContext} that reads the Assertion
 * from the ThreadLocal. The Assertion stored in a ThreadLocal is an artifact of the Jasig CAS Client for Java 3.x
 * library.
 *
 * @author Scott Battaglia
 * @version $Revision$ $Date$
 * @since 3.2
 */
public class CasAssertionSecurityContext extends ChainingSecurityContext implements ICasSecurityContext {
    private static final String SESSION_ADDITIONAL_DESCRIPTORS_BEAN = "sessionScopeAdditionalDescriptors";
    private static final String CAS_COPY_ASSERT_ATTR_TO_USER_ATTR_BEAN = "casCopyAssertionAttributesToUserAttributes";
    private static final String DECRYPT_CRED_TO_PWD = "decryptCredentialToPassword";
    private static final String DECRYPT_CRED_TO_PWD_KEY = "decryptCredentialToPasswordPrivateKey";
    private static final String DECRYPT_CRED_TO_PWD_ALG = "decryptCredentialToPasswordAlgorithm";
    private static final String CREDENTIAL_KEY = "credential";  // encrypted password attribute from CAS
    private static final String PASSWORD_KEY = "password";  // user attribute expected by portlets

    // UP-4212 Transient because security contexts are serialized into HTTP Session (and webflow).
    private transient ApplicationContext applicationContext;
    private Assertion assertion;
    private boolean copyAssertionAttributesToUserAttributes = false;
    private boolean decryptCredentialToPassword = false;
    private String decryptCredentialToPasswordPrivateKey;
    private static PrivateKey key = null;
    private static Cipher cipher = null;
    private static String algorithm;

    public CasAssertionSecurityContext() {
        applicationContext = ApplicationContextLocator.getApplicationContext();
        String propertyVal = applicationContext.getBean(CAS_COPY_ASSERT_ATTR_TO_USER_ATTR_BEAN, String.class);
        copyAssertionAttributesToUserAttributes = "true".equalsIgnoreCase(propertyVal);
        propertyVal = applicationContext.getBean(DECRYPT_CRED_TO_PWD, String.class);
        decryptCredentialToPassword = "true".equalsIgnoreCase(propertyVal);

        if (decryptCredentialToPassword) {
            decryptCredentialToPasswordPrivateKey = applicationContext.getBean(DECRYPT_CRED_TO_PWD_KEY, String.class);
            if (key == null) {
                try {
                    key = getPrivateKeyFromFile(decryptCredentialToPasswordPrivateKey);
                } catch (Exception e) {
                    log.error("Cannot load key from file: {}", decryptCredentialToPasswordPrivateKey);
                    e.printStackTrace();
                }
            }
            if (cipher == null) {
                try {
                    cipher = Cipher.getInstance(key.getAlgorithm());
                } catch (Exception e) {
                    log.error("Cannot create cipher for key from file: {}", decryptCredentialToPasswordPrivateKey);
                    e.printStackTrace();
                }
            }
            algorithm = applicationContext.getBean(DECRYPT_CRED_TO_PWD_ALG, String.class);
        }
    }

    public int getAuthType() {
        return CAS_AUTHTYPE;
    }

    /**
     * Exposes a template post-authentication method for subclasses to implement their custom logic in.
     * <p>
     * NOTE: This is called BEFORE super.authenticate();
     *
     * @param assertion the Assertion that was retrieved from the ThreadLocal.  CANNOT be NULL.
     */
    protected void postAuthenticate(final Assertion assertion) {
        copyAssertionAttributesToUserAttributes(assertion);
    }

    @Override
    public final void authenticate() throws PortalSecurityException {
        if (log.isTraceEnabled()) {
            log.trace("Authenticating user via CAS.");
        }

        this.isauth = false;
        this.assertion = AssertionHolder.getAssertion();

        if (this.assertion != null) {

            final String usernameFromCas = assertion.getPrincipal().getName();

            if (null == usernameFromCas) {
                throw new IllegalStateException("Non-null CAS assertion unexpectedly had null principal name.");
            }

            this.myPrincipal.setUID(usernameFromCas);

            // verify that the principal UID was successfully set
            final String uidAsSetInThePrincipal = this.myPrincipal.getUID();

            if (! usernameFromCas.equals(uidAsSetInThePrincipal) ) {
                final String logMessage = "Attempted to set portal principal username to [" + usernameFromCas +
                        "] as read from the CAS assertion, but uid as set in the principal is instead [" + uidAsSetInThePrincipal + "].  This may be an attempt to exploit CVE-2014-5059 / UP-4192 .";
                log.error(logMessage);
                throw new IllegalStateException(logMessage);
            }

            this.isauth = true;
            log.debug("CASContext authenticated [" + this.myPrincipal.getUID() + "] using assertion [" + this.assertion + "]");
            postAuthenticate(assertion);
        }

        this.myAdditionalDescriptor = null; //no additional descriptor from CAS
        super.authenticate();
        if (log.isTraceEnabled()) {
            log.trace("Finished CAS Authentication");
        }
    }

    public final String getCasServiceToken(final String target) throws CasProxyTicketAcquisitionException {
        if (log.isTraceEnabled()) {
            log.trace("Attempting to retrieve proxy ticket for target [" + target + "] by using CAS Assertion [" + assertion + "]");
        }

        if (this.assertion == null) {
            if (log.isDebugEnabled()) {
                log.debug("No Assertion found for user.  Returning null Proxy Ticket.");
            }
            return null;
        }

        final String proxyTicket = this.assertion.getPrincipal().getProxyTicketFor(target);

        if (proxyTicket == null) {
            log.error("Failed to retrieve proxy ticket for assertion [" + assertion + "].  Is the PGT still valid?");
            throw new CasProxyTicketAcquisitionException(target, assertion.getPrincipal());
        }

        if (log.isTraceEnabled()) {
            log.trace("Returning from Proxy Ticket Request with ticket [" + proxyTicket + "]");
        }

        return proxyTicket;
    }

    public String toString() {
        final StringBuilder builder =  new StringBuilder();
        builder.append(this.getClass().getName());
        builder.append(" assertion:");
        builder.append(this.assertion);
        return builder.toString();
    }

    /**
     * If enabled, convert CAS assertion person attributes into uPortal user attributes.
     *
     * @param assertion the Assertion that was retrieved from the ThreadLocal.  CANNOT be NULL.
     */
    protected void copyAssertionAttributesToUserAttributes(Assertion assertion) {
        if (!copyAssertionAttributesToUserAttributes) {
            return;
        }

        // skip this if there are no attributes or if the attribute set is empty.
        if (assertion.getPrincipal().getAttributes() == null || assertion.getPrincipal().getAttributes().isEmpty()) {
            return;
        }

        Map<String, List<Object>> attributes = new HashMap<>();
        // loop over the set of person attributes from CAS...
        for (Map.Entry<String, Object> attrEntry : assertion.getPrincipal().getAttributes().entrySet()) {
            log.debug("Adding attribute '{}' from Assertion with value '{}'; runtime type of value is {}",
                    attrEntry.getKey(), attrEntry.getValue(), attrEntry.getValue().getClass().getName());

            // Check for credential
            if (decryptCredentialToPassword && key != null && cipher != null && attrEntry.getKey().equals(CREDENTIAL_KEY)) {
                try {
                    final String encPwd = (String) (attrEntry.getValue() instanceof List
                            ? ((List) attrEntry.getValue()).get(0) : attrEntry.getValue());
                    byte[] cred64 = Base64.getDecoder().decode(encPwd);
                    cipher.init(Cipher.DECRYPT_MODE, key);
                    final byte[] cipherData = cipher.doFinal(cred64);
                    final String pwd = new String(cipherData);
                    attributes.put(PASSWORD_KEY, Arrays.asList((Object) pwd));
                } catch (Exception e) {
                    log.warn("Cannot decipher credential", e);
                    e.printStackTrace();
                }
            }

            // convert each attribute to a list, if necessary...
            List<Object> valueList = null;
            if (attrEntry.getValue() instanceof List) {
                valueList = (List)attrEntry.getValue();
            } else {
                valueList = Arrays.asList(attrEntry.getValue());
            }

            // add the attribute...
            attributes.put(attrEntry.getKey(), valueList);
        }

        // get the attribute descriptor from Spring...
        IAdditionalDescriptors additionalDescriptors = (IAdditionalDescriptors) applicationContext.getBean(SESSION_ADDITIONAL_DESCRIPTORS_BEAN);

        // add the new properties...
        additionalDescriptors.addAttributes(attributes);
    }

    protected static PrivateKey getPrivateKeyFromFile(String filename) throws Exception {

        byte[] keyBytes = Files.readAllBytes(new File(filename).toPath());

        PKCS8EncodedKeySpec spec =
                new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        return kf.generatePrivate(spec);
    }
}
