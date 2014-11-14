/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.sts.token.provider;

import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.sts.STSConstants;
import org.apache.cxf.sts.STSPropertiesMBean;
import org.apache.cxf.sts.request.KeyRequirements;
import org.apache.cxf.sts.request.ReceivedKey;
import org.apache.cxf.sts.request.ReceivedToken;
import org.apache.cxf.sts.request.ReceivedToken.STATE;
import org.apache.cxf.sts.request.TokenRequirements;
import org.apache.cxf.sts.service.EncryptionProperties;
import org.apache.cxf.ws.security.sts.provider.STSException;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.CryptoType;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.bean.KeyInfoBean;
import org.apache.wss4j.common.saml.bean.KeyInfoBean.CERT_IDENTIFIER;
import org.apache.wss4j.common.saml.bean.SubjectBean;
import org.apache.wss4j.common.saml.builder.SAML1Constants;
import org.apache.wss4j.common.saml.builder.SAML2Constants;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.message.WSSecEncryptedKey;

/**
 * A default implementation of SubjectProvider to create a SAML Assertion. The Subject name is the name
 * of the current principal, the subject name qualifier is a default URL that can be configured, and the
 * subject confirmation method is created according to the token type and key type. If the Subject
 * Confirmation Method is SymmetricKey or PublicKey, the appropriate KeyInfoBean object is created and
 * attached to the Subject.
 */
public class DefaultSubjectProvider implements SubjectProvider {
    
    private static final Logger LOG = LogUtils.getL7dLogger(DefaultSubjectProvider.class);
    private String subjectNameQualifier = "http://cxf.apache.org/sts";
    private String subjectNameIDFormat;
    
    /**
     * Set the SubjectNameQualifier.
     */
    public void setSubjectNameQualifier(String subjectNameQualifier) {
        this.subjectNameQualifier = subjectNameQualifier;
        LOG.fine("Setting Subject Name Qualifier: " + subjectNameQualifier);
    }
    
    /**
     * Set the SubjectNameIDFormat.
     */
    public void setSubjectNameIDFormat(String subjectNameIDFormat) {
        this.subjectNameIDFormat = subjectNameIDFormat;
        LOG.fine("Setting Subject Name format: " + subjectNameIDFormat);
    }

    /**
     * Get a SubjectBean object.
     */
    public SubjectBean getSubject(
        TokenProviderParameters providerParameters, Document doc, byte[] secret
    ) {
        TokenRequirements tokenRequirements = providerParameters.getTokenRequirements();
        KeyRequirements keyRequirements = providerParameters.getKeyRequirements();
        STSPropertiesMBean stsProperties = providerParameters.getStsProperties();

        String tokenType = tokenRequirements.getTokenType();
        String keyType = keyRequirements.getKeyType();
        String confirmationMethod = getSubjectConfirmationMethod(tokenType, keyType);
        
        Principal principal = null;
        ReceivedToken receivedToken = null;
        //TokenValidator in IssueOperation has validated the ReceivedToken
        //if validation was successful, the principal was set in ReceivedToken 
        if (providerParameters.getTokenRequirements().getOnBehalfOf() != null) {
            receivedToken = providerParameters.getTokenRequirements().getOnBehalfOf();    
            if (receivedToken.getState().equals(STATE.VALID)) {
                principal = receivedToken.getPrincipal();
            }
        } else if (providerParameters.getTokenRequirements().getActAs() != null) {
            receivedToken = providerParameters.getTokenRequirements().getActAs();
            if (receivedToken.getState().equals(STATE.VALID)) {
                principal = receivedToken.getPrincipal();
            }
        } else if (providerParameters.getTokenRequirements().getValidateTarget() != null) {
            receivedToken = providerParameters.getTokenRequirements().getValidateTarget();
            if (receivedToken.getState().equals(STATE.VALID)) {
                principal = receivedToken.getPrincipal();
            }
        } else {
            principal = providerParameters.getPrincipal();
        }
        
        if (principal == null) {
            LOG.fine("Error in getting principal");
            throw new STSException("Error in getting principal", STSException.REQUEST_FAILED);
        }
        
        SubjectBean subjectBean = 
            new SubjectBean(principal.getName(), subjectNameQualifier, confirmationMethod);
        LOG.fine("Creating new subject with principal name: " + principal.getName());
        if (subjectNameIDFormat != null && subjectNameIDFormat.length() > 0) {
            subjectBean.setSubjectNameIDFormat(subjectNameIDFormat);
        }
        
        if (STSConstants.SYMMETRIC_KEY_KEYTYPE.equals(keyType)) {
            Crypto crypto = stsProperties.getEncryptionCrypto();

            EncryptionProperties encryptionProperties = providerParameters.getEncryptionProperties();
            String encryptionName = encryptionProperties.getEncryptionName();
            if (encryptionName == null) {
                // Fall back on the STS encryption name
                encryptionName = stsProperties.getEncryptionUsername();
            }
            if (encryptionName == null) {
                LOG.fine("No encryption Name is configured for Symmetric KeyType");
                throw new STSException("No Encryption Name is configured", STSException.REQUEST_FAILED);
            }
            
            CryptoType cryptoType = null;

            // Check for using of service endpoint (AppliesTo) as certificate identifier
            if (STSConstants.USE_ENDPOINT_AS_CERT_ALIAS.equals(encryptionName)) {
                if (providerParameters.getAppliesToAddress() == null) {
                    throw new STSException("AppliesTo is not initilaized for encryption name "
                                           + STSConstants.USE_ENDPOINT_AS_CERT_ALIAS);
                }
                cryptoType = new CryptoType(CryptoType.TYPE.ENDPOINT);
                cryptoType.setEndpoint(providerParameters.getAppliesToAddress());
            } else {
                cryptoType = new CryptoType(CryptoType.TYPE.ALIAS);
                cryptoType.setAlias(encryptionName);
            }

            try {
                X509Certificate[] certs = crypto.getX509Certificates(cryptoType);
                if ((certs == null) || (certs.length == 0)) {
                    throw new STSException("Encryption certificate is not found for alias: " + encryptionName);
                }
                KeyInfoBean keyInfo = 
                    createKeyInfo(certs[0], secret, doc, encryptionProperties, crypto);
                subjectBean.setKeyInfo(keyInfo);
            } catch (WSSecurityException ex) {
                LOG.log(Level.WARNING, "", ex);
                throw new STSException(ex.getMessage(), ex);
            }
        } else if (STSConstants.PUBLIC_KEY_KEYTYPE.equals(keyType)) {
            ReceivedKey receivedKey = keyRequirements.getReceivedKey();
            
            // Validate UseKey trust
            if (stsProperties.isValidateUseKey() && stsProperties.getSignatureCrypto() != null) {
                if (receivedKey.getX509Cert() != null) {
                    try {
                        Collection<Pattern> constraints = Collections.emptyList();
                        stsProperties.getSignatureCrypto().verifyTrust(
                            new X509Certificate[]{receivedKey.getX509Cert()}, false, constraints);
                    } catch (WSSecurityException e) {
                        LOG.log(Level.FINE, "Error in trust validation of UseKey: ", e);
                        throw new STSException("Error in trust validation of UseKey", STSException.REQUEST_FAILED);
                    }
                }
                if (receivedKey.getPublicKey() != null) {
                    try {
                        stsProperties.getSignatureCrypto().verifyTrust(receivedKey.getPublicKey());
                    } catch (WSSecurityException e) {
                        LOG.log(Level.FINE, "Error in trust validation of UseKey: ", e);
                        throw new STSException("Error in trust validation of UseKey", STSException.REQUEST_FAILED);
                    }
                }
            }
            
            KeyInfoBean keyInfo = createKeyInfo(receivedKey.getX509Cert(), receivedKey.getPublicKey());
            subjectBean.setKeyInfo(keyInfo);
        }
        
        return subjectBean;
    }
        
    /**
     * Get the SubjectConfirmation method given a tokenType and keyType
     */
    protected String getSubjectConfirmationMethod(String tokenType, String keyType) {
        if (WSConstants.WSS_SAML2_TOKEN_TYPE.equals(tokenType)
            || WSConstants.SAML2_NS.equals(tokenType)) {
            if (STSConstants.SYMMETRIC_KEY_KEYTYPE.equals(keyType) 
                || STSConstants.PUBLIC_KEY_KEYTYPE.equals(keyType)) {
                return SAML2Constants.CONF_HOLDER_KEY;
            } else {
                return SAML2Constants.CONF_BEARER;
            }
        } else {
            if (STSConstants.SYMMETRIC_KEY_KEYTYPE.equals(keyType) 
                || STSConstants.PUBLIC_KEY_KEYTYPE.equals(keyType)) {
                return SAML1Constants.CONF_HOLDER_KEY;
            } else {
                return SAML1Constants.CONF_BEARER;
            }
        }
    }

    /**
     * Create a KeyInfoBean that contains an X.509 certificate or Public Key
     */
    protected static KeyInfoBean createKeyInfo(X509Certificate certificate, PublicKey publicKey) {
        KeyInfoBean keyInfo = new KeyInfoBean();

        if (certificate != null) {
            keyInfo.setCertificate(certificate);
            keyInfo.setCertIdentifer(CERT_IDENTIFIER.X509_CERT);
        } else if (publicKey != null) {
            keyInfo.setPublicKey(publicKey);
            keyInfo.setCertIdentifer(CERT_IDENTIFIER.KEY_VALUE);
        }

        return keyInfo;
    }

    /**
     * Create an EncryptedKey KeyInfo.
     */
    protected static KeyInfoBean createKeyInfo(
        X509Certificate certificate, 
        byte[] secret,
        Document doc,
        EncryptionProperties encryptionProperties,
        Crypto encryptionCrypto
    ) throws WSSecurityException {
        KeyInfoBean keyInfo = new KeyInfoBean();

        // Create an EncryptedKey
        WSSecEncryptedKey encrKey = new WSSecEncryptedKey();
        encrKey.setKeyIdentifierType(encryptionProperties.getKeyIdentifierType());
        encrKey.setEphemeralKey(secret);
        encrKey.setSymmetricEncAlgorithm(encryptionProperties.getEncryptionAlgorithm());
        encrKey.setUseThisCert(certificate);
        encrKey.setKeyEncAlgo(encryptionProperties.getKeyWrapAlgorithm());
        encrKey.prepare(doc, encryptionCrypto);
        Element encryptedKeyElement = encrKey.getEncryptedKeyElement();

        // Append the EncryptedKey to a KeyInfo element
        Element keyInfoElement = 
            doc.createElementNS(
                WSConstants.SIG_NS, WSConstants.SIG_PREFIX + ":" + WSConstants.KEYINFO_LN
            );
        keyInfoElement.setAttributeNS(
            WSConstants.XMLNS_NS, "xmlns:" + WSConstants.SIG_PREFIX, WSConstants.SIG_NS
        );
        keyInfoElement.appendChild(encryptedKeyElement);

        keyInfo.setElement(keyInfoElement);

        return keyInfo;
    }
}
