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

package org.apache.cxf.ws.security.wss4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.stream.XMLStreamException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.helpers.MapNamespaceContext;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.security.transport.TLSSessionInfo;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.policy.PolicyUtils;
import org.apache.cxf.ws.security.wss4j.CryptoCoverageUtil.CoverageScope;
import org.apache.cxf.ws.security.wss4j.CryptoCoverageUtil.CoverageType;
import org.apache.cxf.ws.security.wss4j.policyvalidators.AlgorithmSuitePolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.AsymmetricBindingPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.ConcreteSupportingTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.EncryptedTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.EndorsingEncryptedTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.EndorsingTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.LayoutPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.PolicyValidatorParameters;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SamlTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SecurityContextTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SecurityPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SignedEncryptedTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SignedEndorsingEncryptedTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SignedEndorsingTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SignedTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.SymmetricBindingPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.TransportBindingPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.UsernameTokenPolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.WSS11PolicyValidator;
import org.apache.cxf.ws.security.wss4j.policyvalidators.X509TokenPolicyValidator;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.PasswordEncryptor;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDataRef;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.message.token.Timestamp;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.wss4j.policy.SP11Constants;
import org.apache.wss4j.policy.SP12Constants;
import org.apache.wss4j.policy.SP13Constants;
import org.apache.wss4j.policy.SPConstants;
import org.apache.wss4j.policy.model.AlgorithmSuite;
import org.apache.wss4j.policy.model.Attachments;
import org.apache.wss4j.policy.model.Header;
import org.apache.wss4j.policy.model.RequiredElements;
import org.apache.wss4j.policy.model.RequiredParts;
import org.apache.wss4j.policy.model.SignedParts;
import org.apache.wss4j.policy.model.UsernameToken;
import org.apache.wss4j.policy.model.UsernameToken.PasswordType;
import org.apache.wss4j.policy.model.Wss11;

/**
 * 
 */
public class PolicyBasedWSS4JInInterceptor extends WSS4JInInterceptor {
    public static final PolicyBasedWSS4JInInterceptor INSTANCE 
        = new PolicyBasedWSS4JInInterceptor();
    private static final Logger LOG = LogUtils.getL7dLogger(PolicyBasedWSS4JInInterceptor.class);

    /**
     * 
     */
    public PolicyBasedWSS4JInInterceptor() {
        super(true);
    }
    
    public void handleMessage(SoapMessage msg) throws Fault {
        AssertionInfoMap aim = msg.get(AssertionInfoMap.class);
        boolean enableStax = 
            MessageUtils.isTrue(msg.getContextualProperty(SecurityConstants.ENABLE_STREAMING_SECURITY));
        if (aim != null && !enableStax) {
            super.handleMessage(msg);
        }
    }
    
    private void handleWSS11(AssertionInfoMap aim, SoapMessage message) {
        if (isRequestor(message)) {
            message.put(WSHandlerConstants.ENABLE_SIGNATURE_CONFIRMATION, "false");
            Collection<AssertionInfo> ais = 
                PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.WSS11);
            if (!ais.isEmpty()) {
                for (AssertionInfo ai : ais) {
                    Wss11 wss11 = (Wss11)ai.getAssertion();
                    if (wss11.isRequireSignatureConfirmation()) {
                        message.put(WSHandlerConstants.ENABLE_SIGNATURE_CONFIRMATION, "true");
                        break;
                    }
                }
            }
        }
    }

    private String addToAction(String action, String val, boolean pre) {
        if (action.contains(val)) {
            return action;
        }
        if (pre) {
            return val + " " + action; 
        } 
        return action + " " + val;
    }
    
    private String checkAsymmetricBinding(
        AssertionInfoMap aim, String action, SoapMessage message, RequestData data
    ) throws WSSecurityException {
        AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.ASYMMETRIC_BINDING);
        if (ai == null) {
            return action;
        }
        
        action = addToAction(action, "Signature", true);
        action = addToAction(action, "Encrypt", true);
        Object s = message.getContextualProperty(SecurityConstants.SIGNATURE_CRYPTO);
        if (s == null) {
            s = message.getContextualProperty(SecurityConstants.SIGNATURE_PROPERTIES);
        }
        Object e = message.getContextualProperty(SecurityConstants.ENCRYPT_CRYPTO);
        if (e == null) {
            e = message.getContextualProperty(SecurityConstants.ENCRYPT_PROPERTIES);
        }
        
        Crypto encrCrypto = getEncryptionCrypto(e, message, data);
        Crypto signCrypto = null;
        if (e != null && e.equals(s)) {
            signCrypto = encrCrypto;
        } else {
            signCrypto = getSignatureCrypto(s, message, data);
        }
        
        if (signCrypto != null) {
            message.put(WSHandlerConstants.DEC_PROP_REF_ID, "RefId-" + signCrypto.hashCode());
            message.put("RefId-" + signCrypto.hashCode(), signCrypto);
        }
        
        if (encrCrypto != null) {
            message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + encrCrypto.hashCode());
            message.put("RefId-" + encrCrypto.hashCode(), (Crypto)encrCrypto);
        } else if (signCrypto != null) {
            message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + signCrypto.hashCode());
            message.put("RefId-" + signCrypto.hashCode(), (Crypto)signCrypto);
        }
     
        return action;
    }
    
    private String checkDefaultBinding(
        AssertionInfoMap aim, String action, SoapMessage message, RequestData data
    ) throws WSSecurityException {
        action = addToAction(action, "Signature", true);
        action = addToAction(action, "Encrypt", true);
        Object s = message.getContextualProperty(SecurityConstants.SIGNATURE_CRYPTO);
        if (s == null) {
            s = message.getContextualProperty(SecurityConstants.SIGNATURE_PROPERTIES);
        }
        Object e = message.getContextualProperty(SecurityConstants.ENCRYPT_CRYPTO);
        if (e == null) {
            e = message.getContextualProperty(SecurityConstants.ENCRYPT_PROPERTIES);
        }
        
        Crypto encrCrypto = getEncryptionCrypto(e, message, data);
        Crypto signCrypto = null;
        if (e != null && e.equals(s)) {
            signCrypto = encrCrypto;
        } else {
            signCrypto = getSignatureCrypto(s, message, data);
        }
        
        if (signCrypto != null) {
            message.put(WSHandlerConstants.DEC_PROP_REF_ID, "RefId-" + signCrypto.hashCode());
            message.put("RefId-" + signCrypto.hashCode(), signCrypto);
        }
        
        if (encrCrypto != null) {
            message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + encrCrypto.hashCode());
            message.put("RefId-" + encrCrypto.hashCode(), (Crypto)encrCrypto);
        } else if (signCrypto != null) {
            message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + signCrypto.hashCode());
            message.put("RefId-" + signCrypto.hashCode(), (Crypto)signCrypto);
        }

        return action;
    }
    
    /**
     * Is a Nonce Cache required, i.e. are we expecting a UsernameToken
     */
    @Override
    protected boolean isNonceCacheRequired(List<Integer> actions, SoapMessage msg) {
        AssertionInfoMap aim = msg.get(AssertionInfoMap.class);
        if (aim != null) {
            AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.USERNAME_TOKEN);
            if (ai != null) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Is a Timestamp cache required, i.e. are we expecting a Timestamp 
     */
    @Override
    protected boolean isTimestampCacheRequired(List<Integer> actions, SoapMessage msg) {
        AssertionInfoMap aim = msg.get(AssertionInfoMap.class);
        if (aim != null) {
            AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.INCLUDE_TIMESTAMP);
            if (ai != null) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Is a SAML Cache required, i.e. are we expecting a SAML Token 
     */
    @Override
    protected boolean isSamlCacheRequired(List<Integer> actions, SoapMessage msg) {
        AssertionInfoMap aim = msg.get(AssertionInfoMap.class);
        if (aim != null) {
            AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.SAML_TOKEN);
            if (ai != null) {
                return true;
            }
        }
        
        return false;
    }
    
    private void checkUsernameToken(
        AssertionInfoMap aim, SoapMessage message
    ) throws WSSecurityException {
        Collection<AssertionInfo> ais = 
            PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.USERNAME_TOKEN);
        
        if (!ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                UsernameToken policy = (UsernameToken)ai.getAssertion();
                if (policy.getPasswordType() == PasswordType.NoPassword) {
                    message.put(WSHandlerConstants.ALLOW_USERNAMETOKEN_NOPASSWORD, "true");
                }
            }
        }
    }
    
    private String checkSymmetricBinding(
        AssertionInfoMap aim, String action, SoapMessage message, RequestData data
    ) throws WSSecurityException {
        AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.SYMMETRIC_BINDING);
        if (ai == null) {
            return action;
        }
        
        action = addToAction(action, "Signature", true);
        action = addToAction(action, "Encrypt", true);
        Object s = message.getContextualProperty(SecurityConstants.SIGNATURE_CRYPTO);
        if (s == null) {
            s = message.getContextualProperty(SecurityConstants.SIGNATURE_PROPERTIES);
        }
        Object e = message.getContextualProperty(SecurityConstants.ENCRYPT_CRYPTO);
        if (e == null) {
            e = message.getContextualProperty(SecurityConstants.ENCRYPT_PROPERTIES);
        }
        
        Crypto encrCrypto = getEncryptionCrypto(e, message, data);
        Crypto signCrypto = null;
        if (e != null && e.equals(s)) {
            signCrypto = encrCrypto;
        } else {
            signCrypto = getSignatureCrypto(s, message, data);
        }
        
        if (isRequestor(message)) {
            Crypto crypto = encrCrypto;
            if (crypto == null) {
                crypto = signCrypto;
            }
            if (crypto != null) {
                message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + crypto.hashCode());
                message.put("RefId-" + crypto.hashCode(), crypto);
            }
            
            crypto = signCrypto;
            if (crypto == null) {
                crypto = encrCrypto;
            }
            if (crypto != null) {
                message.put(WSHandlerConstants.DEC_PROP_REF_ID, "RefId-" + crypto.hashCode());
                message.put("RefId-" + crypto.hashCode(), crypto);
            }
        } else {
            Crypto crypto = signCrypto;
            if (crypto == null) {
                crypto = encrCrypto;
            }
            if (crypto != null) {
                message.put(WSHandlerConstants.SIG_VER_PROP_REF_ID, "RefId-" + crypto.hashCode());
                message.put("RefId-" + crypto.hashCode(), crypto);
            }
            
            crypto = encrCrypto;
            if (crypto == null) {
                crypto = signCrypto;
            }
            if (crypto != null) {
                message.put(WSHandlerConstants.DEC_PROP_REF_ID, "RefId-" + crypto.hashCode());
                message.put("RefId-" + crypto.hashCode(), crypto);
            }
        }
        
        return action;
    }
    
    private Crypto getEncryptionCrypto(Object e, 
                                       SoapMessage message, 
                                       RequestData requestData) throws WSSecurityException {
        PasswordEncryptor passwordEncryptor = getPasswordEncryptor(message, requestData);
        return WSS4JUtils.getEncryptionCrypto(e, message, passwordEncryptor);
    }
    
    private PasswordEncryptor getPasswordEncryptor(SoapMessage soapMessage, RequestData requestData) {
        PasswordEncryptor passwordEncryptor = 
            (PasswordEncryptor)soapMessage.getContextualProperty(
                SecurityConstants.PASSWORD_ENCRYPTOR_INSTANCE
            );
        if (passwordEncryptor != null) {
            return passwordEncryptor;
        }
        
        return super.getPasswordEncryptor(requestData);
    }
    
    private Crypto getSignatureCrypto(Object s, SoapMessage message, 
                                      RequestData requestData) throws WSSecurityException {
        PasswordEncryptor passwordEncryptor = getPasswordEncryptor(message, requestData);
        return WSS4JUtils.getSignatureCrypto(s, message, passwordEncryptor);
    }
    
    private boolean assertXPathTokens(AssertionInfoMap aim, 
                                   String name, 
                                   Collection<WSDataRef> refs,
                                   Element soapEnvelope,
                                   CoverageType type,
                                   CoverageScope scope,
                                   final XPath xpath) throws SOAPException {
        Collection<AssertionInfo> ais = PolicyUtils.getAllAssertionsByLocalname(aim, name);
        if (!ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
                
                RequiredElements elements = (RequiredElements)ai.getAssertion();
                
                if (elements != null && elements.getXPaths() != null && !elements.getXPaths().isEmpty()) {
                    List<String> expressions = new ArrayList<>();
                    MapNamespaceContext namespaceContext = new MapNamespaceContext();
                    
                    for (org.apache.wss4j.policy.model.XPath xPath : elements.getXPaths()) {
                        expressions.add(xPath.getXPath());
                        Map<String, String> namespaceMap = xPath.getPrefixNamespaceMap();
                        if (namespaceMap != null) {
                            namespaceContext.addNamespaces(namespaceMap);
                        }
                    }

                    xpath.setNamespaceContext(namespaceContext);
                    try {
                        CryptoCoverageUtil.checkCoverage(soapEnvelope, refs,
                                                         xpath, expressions, type, scope);
                    } catch (WSSecurityException e) {
                        ai.setNotAsserted("No " + type 
                                          + " element found matching one of the XPaths " 
                                          + Arrays.toString(expressions.toArray()));
                    }
                }
            }
        }
        return true;
    }

    
    private boolean assertTokens(AssertionInfoMap aim, 
                              String name, 
                              Collection<WSDataRef> signed,
                              SoapMessage msg,
                              Element soapHeader,
                              Element soapBody,
                              CoverageType type) throws SOAPException {
        Collection<AssertionInfo> ais = PolicyUtils.getAllAssertionsByLocalname(aim, name);
        if (!ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
                SignedParts p = (SignedParts)ai.getAssertion();
                
                if (p.isBody()) {
                    try {
                        if (CoverageType.SIGNED.equals(type)) {
                            CryptoCoverageUtil.checkBodyCoverage(
                                soapBody, signed, type, CoverageScope.ELEMENT
                            );
                        } else {
                            CryptoCoverageUtil.checkBodyCoverage(
                                soapBody, signed, type, CoverageScope.CONTENT
                            );
                        }
                    } catch (WSSecurityException e) {
                        ai.setNotAsserted(msg.getVersion().getBody() + " not " + type);
                        continue;
                    }
                }
                
                for (Header h : p.getHeaders()) {
                    try {
                        CryptoCoverageUtil.checkHeaderCoverage(soapHeader, signed, h
                                .getNamespace(), h.getName(), type,
                                CoverageScope.ELEMENT);
                    } catch (WSSecurityException e) {
                        ai.setNotAsserted(h.getNamespace() + ":" + h.getName() + " not + " + type);
                    }
                }
                
                Attachments attachments = p.getAttachments();
                if (attachments != null) {
                    try {
                        CoverageScope scope = CoverageScope.ELEMENT;
                        if (attachments.isContentSignatureTransform()) {
                            scope = CoverageScope.CONTENT;
                        }
                        CryptoCoverageUtil.checkAttachmentsCoverage(msg.getAttachments(), signed, 
                                                                type, scope);
                    } catch (WSSecurityException e) {
                        ai.setNotAsserted("An attachment was not signed/encrypted");
                    }
                }
            }
        }
        return true;
    }
    
    
    /**
     * Set a WSS4J AlgorithmSuite object on the RequestData context, to restrict the
     * algorithms that are allowed for encryption, signature, etc.
     */
    protected void setAlgorithmSuites(SoapMessage message, RequestData data) throws WSSecurityException {
        AlgorithmSuiteTranslater translater = new AlgorithmSuiteTranslater();
        translater.translateAlgorithmSuites(message.get(AssertionInfoMap.class), data);
        
        // Allow for setting non-standard asymmetric signature algorithms
        String asymSignatureAlgorithm = 
            (String)message.getContextualProperty(SecurityConstants.ASYMMETRIC_SIGNATURE_ALGORITHM);
        if (asymSignatureAlgorithm != null && data.getAlgorithmSuite() != null) {
            data.getAlgorithmSuite().getSignatureMethods().clear();
            data.getAlgorithmSuite().getSignatureMethods().add(asymSignatureAlgorithm);
        }
    }

    protected void computeAction(SoapMessage message, RequestData data) throws WSSecurityException {
        String action = getString(WSHandlerConstants.ACTION, message);
        if (action == null) {
            action = "";
        }
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (aim != null) {
            //things that DO impact setup
            handleWSS11(aim, message);
            action = checkAsymmetricBinding(aim, action, message, data);
            action = checkSymmetricBinding(aim, action, message, data);
            Collection<AssertionInfo> ais = aim.get(SP12Constants.TRANSPORT_BINDING);
            if ("".equals(action) || (ais != null && !ais.isEmpty())) {
                action = checkDefaultBinding(aim, action, message, data);
            }
            
            // Allow for setting non-standard asymmetric signature algorithms
            String asymSignatureAlgorithm = 
                (String)message.getContextualProperty(SecurityConstants.ASYMMETRIC_SIGNATURE_ALGORITHM);
            if (asymSignatureAlgorithm != null) {
                Collection<AssertionInfo> algorithmSuites = 
                    aim.get(SP12Constants.ALGORITHM_SUITE);
                if (algorithmSuites != null && !algorithmSuites.isEmpty()) {
                    for (AssertionInfo algorithmSuite : algorithmSuites) {
                        AlgorithmSuite algSuite = (AlgorithmSuite)algorithmSuite.getAssertion();
                        algSuite.setAsymmetricSignature(asymSignatureAlgorithm);
                    }
                }
            }
            
            checkUsernameToken(aim, message);
            
            // stuff we can default to asserted and un-assert if a condition isn't met
            PolicyUtils.assertPolicy(aim, SPConstants.KEY_VALUE_TOKEN);
            PolicyUtils.assertPolicy(aim, SPConstants.RSA_KEY_VALUE);
            
            // WSS10
            ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.WSS10);
            if (!ais.isEmpty()) {
                for (AssertionInfo ai : ais) {
                    ai.setAsserted(true);
                }
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_REF_KEY_IDENTIFIER);
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_REF_ISSUER_SERIAL);
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_REF_EXTERNAL_URI);
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_REF_EMBEDDED_TOKEN);
            }
            
            // Trust 1.0
            ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.TRUST_10);
            boolean trust10Asserted = false;
            if (!ais.isEmpty()) {
                for (AssertionInfo ai : ais) {
                    ai.setAsserted(true);
                }
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_CLIENT_CHALLENGE);
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_SERVER_CHALLENGE);
                PolicyUtils.assertPolicy(aim, SPConstants.REQUIRE_CLIENT_ENTROPY);
                PolicyUtils.assertPolicy(aim, SPConstants.REQUIRE_SERVER_ENTROPY);
                PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_ISSUED_TOKENS);
                trust10Asserted = true;
            }
            
            // Trust 1.3
            ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.TRUST_13);
            if (!ais.isEmpty()) {
                for (AssertionInfo ai : ais) {
                    ai.setAsserted(true);
                }
                PolicyUtils.assertPolicy(aim, SP12Constants.REQUIRE_REQUEST_SECURITY_TOKEN_COLLECTION);
                PolicyUtils.assertPolicy(aim, SP12Constants.REQUIRE_APPLIES_TO);
                PolicyUtils.assertPolicy(aim, SP13Constants.SCOPE_POLICY_15);
                PolicyUtils.assertPolicy(aim, SP13Constants.MUST_SUPPORT_INTERACTIVE_CHALLENGE);
                
                if (!trust10Asserted) {
                    PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_CLIENT_CHALLENGE);
                    PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_SERVER_CHALLENGE);
                    PolicyUtils.assertPolicy(aim, SPConstants.REQUIRE_CLIENT_ENTROPY);
                    PolicyUtils.assertPolicy(aim, SPConstants.REQUIRE_SERVER_ENTROPY);
                    PolicyUtils.assertPolicy(aim, SPConstants.MUST_SUPPORT_ISSUED_TOKENS);
                }
            }
            
            message.put(WSHandlerConstants.ACTION, action.trim());
        }
    }
    
    @Override
    protected void doResults(
        SoapMessage msg, 
        String actor,
        Element soapHeader,
        Element soapBody,
        WSHandlerResult results, 
        boolean utWithCallbacks
    ) throws SOAPException, XMLStreamException, WSSecurityException {
        //
        // Pre-fetch various results
        //
        final List<Integer> actions = new ArrayList<>(3);
        actions.add(WSConstants.SIGN);
        actions.add(WSConstants.UT_SIGN);
        actions.add(WSConstants.ST_SIGNED);
        List<WSSecurityEngineResult> signedResults = 
            WSSecurityUtil.fetchAllActionResults(results.getResults(), actions);
        Collection<WSDataRef> signed = new HashSet<>();
        for (WSSecurityEngineResult result : signedResults) {
            List<WSDataRef> sl = 
                CastUtils.cast((List<?>)result.get(WSSecurityEngineResult.TAG_DATA_REF_URIS));
            if (sl != null) {
                for (WSDataRef r : sl) {
                    signed.add(r);
                }
            }
        }
        
        List<WSSecurityEngineResult> encryptResults = 
            WSSecurityUtil.fetchAllActionResults(results.getResults(), WSConstants.ENCR);
        Collection<WSDataRef> encrypted = new HashSet<>();
        for (WSSecurityEngineResult result : encryptResults) {
            List<WSDataRef> sl = 
                CastUtils.cast((List<?>)result.get(WSSecurityEngineResult.TAG_DATA_REF_URIS));
            if (sl != null) {
                for (WSDataRef r : sl) {
                    encrypted.add(r);
                }
            }
        }
        
        //
        // Check policies
        //
        AssertionInfoMap aim = msg.get(AssertionInfoMap.class);
        if (!checkSignedEncryptedCoverage(aim, msg, soapHeader, soapBody, signed, encrypted)) {
            LOG.fine("Incoming request failed signed-encrypted policy validation");
        }
        
        PolicyValidatorParameters parameters = new PolicyValidatorParameters();
        parameters.setAssertionInfoMap(aim);
        parameters.setMessage(msg);
        parameters.setSoapBody(soapBody);
        parameters.setResults(results.getResults());
        parameters.setSignedResults(signedResults);
        parameters.setEncryptedResults(encryptResults);
        parameters.setUtWithCallbacks(utWithCallbacks);
        
        final List<Integer> utActions = new ArrayList<>(2);
        utActions.add(WSConstants.UT);
        utActions.add(WSConstants.UT_NOPASSWORD);
        List<WSSecurityEngineResult> utResults = 
            WSSecurityUtil.fetchAllActionResults(results.getResults(), utActions);
        parameters.setUsernameTokenResults(utResults);
        
        final List<Integer> samlActions = new ArrayList<>(2);
        samlActions.add(WSConstants.ST_SIGNED);
        samlActions.add(WSConstants.ST_UNSIGNED);
        List<WSSecurityEngineResult> samlResults = 
            WSSecurityUtil.fetchAllActionResults(results.getResults(), samlActions);
        parameters.setSamlResults(samlResults);
        
        // Store the timestamp element
        WSSecurityEngineResult tsResult = 
            WSSecurityUtil.fetchActionResult(results.getResults(), WSConstants.TS);
        Element timestamp = null;
        if (tsResult != null) {
            Timestamp ts = (Timestamp)tsResult.get(WSSecurityEngineResult.TAG_TIMESTAMP);
            timestamp = ts.getElement();
        }
        parameters.setTimestampElement(timestamp);
        
        checkTokenCoverage(parameters);
        checkBindingCoverage(parameters);
        checkSupportingTokenCoverage(parameters);
        
        super.doResults(msg, actor, soapHeader, soapBody, results, utWithCallbacks);
    }
    
    /**
     * Check SignedParts, EncryptedParts, SignedElements, EncryptedElements, RequiredParts, etc.
     */
    private boolean checkSignedEncryptedCoverage(
        AssertionInfoMap aim,
        SoapMessage msg,
        Element soapHeader,
        Element soapBody,
        Collection<WSDataRef> signed, 
        Collection<WSDataRef> encrypted
    ) throws SOAPException {
        CryptoCoverageUtil.reconcileEncryptedSignedRefs(signed, encrypted);
        //
        // SIGNED_PARTS and ENCRYPTED_PARTS only apply to non-Transport bindings
        //
        boolean check = true;
        if (!isTransportBinding(aim, msg)) {
            assertTokens(
                aim, SPConstants.SIGNED_PARTS, signed, msg, soapHeader, soapBody, CoverageType.SIGNED
            );
            assertTokens(
                aim, SPConstants.ENCRYPTED_PARTS, encrypted, msg, soapHeader, soapBody, 
                CoverageType.ENCRYPTED
            );
        }
        Element soapEnvelope = soapHeader.getOwnerDocument().getDocumentElement();
        if (containsXPathPolicy(aim)) {
            // XPathFactory and XPath are not thread-safe so we must recreate them
            // each request.
            final XPathFactory factory = XPathFactory.newInstance();
            final XPath xpath = factory.newXPath();
            
            assertXPathTokens(aim, SPConstants.SIGNED_ELEMENTS, signed, soapEnvelope,
                    CoverageType.SIGNED, CoverageScope.ELEMENT, xpath);
            assertXPathTokens(aim, SPConstants.ENCRYPTED_ELEMENTS, encrypted, soapEnvelope,
                    CoverageType.ENCRYPTED, CoverageScope.ELEMENT, xpath);
            assertXPathTokens(aim, SPConstants.CONTENT_ENCRYPTED_ELEMENTS, encrypted, 
                    soapEnvelope, CoverageType.ENCRYPTED, CoverageScope.CONTENT, xpath);
        }
        
        assertHeadersExists(aim, msg, soapHeader);
        return check;
    }
    
    /**
     * Check the token coverage
     */
    private void checkTokenCoverage(PolicyValidatorParameters parameters) {
        
        AssertionInfoMap aim = parameters.getAssertionInfoMap();
        
        Collection<AssertionInfo> ais = 
            PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.X509_TOKEN);
        SecurityPolicyValidator x509Validator = new X509TokenPolicyValidator();
        x509Validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.USERNAME_TOKEN);
        SecurityPolicyValidator utValidator = new UsernameTokenPolicyValidator();
        utValidator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SAML_TOKEN);
        SecurityPolicyValidator samlValidator = new SamlTokenPolicyValidator();
        samlValidator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SECURITY_CONTEXT_TOKEN);
        SecurityPolicyValidator sctValidator = new SecurityContextTokenPolicyValidator();
        sctValidator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.WSS11);
        SecurityPolicyValidator wss11Validator = new WSS11PolicyValidator();
        wss11Validator.validatePolicies(parameters, ais);
    }
    
    /**
     * Check the binding coverage
     */
    private void checkBindingCoverage(PolicyValidatorParameters parameters) {
        AssertionInfoMap aim = parameters.getAssertionInfoMap();
        
        Collection<AssertionInfo> ais = 
            PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.TRANSPORT_BINDING);
        SecurityPolicyValidator transportValidator = new TransportBindingPolicyValidator();
        transportValidator.validatePolicies(parameters, ais);
            
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SYMMETRIC_BINDING);
        SecurityPolicyValidator symmetricValidator = new SymmetricBindingPolicyValidator();
        symmetricValidator.validatePolicies(parameters, ais);

        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.ASYMMETRIC_BINDING);
        SecurityPolicyValidator asymmetricValidator = new AsymmetricBindingPolicyValidator();
        asymmetricValidator.validatePolicies(parameters, ais);
        
        // Check AlgorithmSuite + Layout that might not be tied to a binding
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.ALGORITHM_SUITE);
        SecurityPolicyValidator algorithmSuiteValidator = new AlgorithmSuitePolicyValidator();
        algorithmSuiteValidator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.LAYOUT);
        LayoutPolicyValidator layoutValidator = new LayoutPolicyValidator();
        layoutValidator.validatePolicies(parameters, ais);
    }
    
    /**
     * Check the supporting token coverage
     */
    private void checkSupportingTokenCoverage(PolicyValidatorParameters parameters) {
        AssertionInfoMap aim = parameters.getAssertionInfoMap();
        
        Collection<AssertionInfo> ais = 
            PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SUPPORTING_TOKENS);
        SecurityPolicyValidator validator = new ConcreteSupportingTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SIGNED_SUPPORTING_TOKENS);
        validator = new SignedTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.ENDORSING_SUPPORTING_TOKENS);
        validator = new EndorsingTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SIGNED_ENDORSING_SUPPORTING_TOKENS);
        validator = new SignedEndorsingTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SIGNED_ENCRYPTED_SUPPORTING_TOKENS);
        validator = new SignedEncryptedTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.ENCRYPTED_SUPPORTING_TOKENS);
        validator = new EncryptedTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        validator = new EndorsingEncryptedTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);

        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.SIGNED_ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        validator = new SignedEndorsingEncryptedTokenPolicyValidator();
        validator.validatePolicies(parameters, ais);
    }
    
    private boolean assertHeadersExists(AssertionInfoMap aim, SoapMessage msg, Node header) 
        throws SOAPException {
        
        Collection<AssertionInfo> ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.REQUIRED_PARTS);
        if (!ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                RequiredParts rp = (RequiredParts)ai.getAssertion();
                ai.setAsserted(true);
                for (Header h : rp.getHeaders()) {
                    QName qName = new QName(h.getNamespace(), h.getName());
                    if (header == null || DOMUtils.getFirstChildWithName((Element)header, qName) == null) {
                        ai.setNotAsserted("No header element of name " + qName + " found.");
                    }
                }
            }
        }
        
        ais = PolicyUtils.getAllAssertionsByLocalname(aim, SPConstants.REQUIRED_ELEMENTS);
        if (!ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                RequiredElements rp = (RequiredElements)ai.getAssertion();
                ai.setAsserted(true);
                
                if (rp != null && rp.getXPaths() != null && !rp.getXPaths().isEmpty()) {
                    XPathFactory factory = XPathFactory.newInstance();
                    for (org.apache.wss4j.policy.model.XPath xPath : rp.getXPaths()) {
                        Map<String, String> namespaces = xPath.getPrefixNamespaceMap();
                        String expression = xPath.getXPath();
    
                        XPath xpath = factory.newXPath();
                        if (namespaces != null) {
                            xpath.setNamespaceContext(new MapNamespaceContext(namespaces));
                        }
                        NodeList list;
                        try {
                            list = (NodeList)xpath.evaluate(expression, 
                                                                     header,
                                                                     XPathConstants.NODESET);
                            if (list.getLength() == 0) {
                                ai.setNotAsserted("No header element matching XPath " + expression + " found.");
                            }
                        } catch (XPathExpressionException e) {
                            ai.setNotAsserted("Invalid XPath expression " + expression + " " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        return true;
    }

    private boolean isTransportBinding(AssertionInfoMap aim, SoapMessage message) {
        AssertionInfo symAis = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.SYMMETRIC_BINDING);
        if (symAis != null) {
            return false;
        }
        
        AssertionInfo asymAis = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.ASYMMETRIC_BINDING);
        if (asymAis != null) {
            return false;
        }
        
        AssertionInfo transAis = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.TRANSPORT_BINDING);
        if (transAis != null) {
            return true;
        }
        
        // No bindings, check if we are using TLS
        TLSSessionInfo tlsInfo = message.get(TLSSessionInfo.class);
        if (tlsInfo != null) {
            // We don't need to check these policies for TLS
            PolicyUtils.assertPolicy(aim, SP12Constants.ENCRYPTED_PARTS);
            PolicyUtils.assertPolicy(aim, SP11Constants.ENCRYPTED_PARTS);
            PolicyUtils.assertPolicy(aim, SP12Constants.SIGNED_PARTS);
            PolicyUtils.assertPolicy(aim, SP11Constants.SIGNED_PARTS);
            return true;
        }
        
        return false;
    }
    
    private boolean containsXPathPolicy(AssertionInfoMap aim) {
        AssertionInfo ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.SIGNED_ELEMENTS);
        if (ai != null) {
            return true;
        }
        
        ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.ENCRYPTED_ELEMENTS);
        if (ai != null) {
            return true;
        }
        
        ai = PolicyUtils.getFirstAssertionByLocalname(aim, SPConstants.CONTENT_ENCRYPTED_ELEMENTS);
        if (ai != null) {
            return true;
        }
        
        return false;
    }

}
