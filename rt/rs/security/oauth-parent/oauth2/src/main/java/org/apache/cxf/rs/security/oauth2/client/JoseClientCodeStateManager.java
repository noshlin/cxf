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
package org.apache.cxf.rs.security.oauth2.client;

import java.util.List;
import java.util.Map;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.jaxrs.impl.MetadataMap;
import org.apache.cxf.jaxrs.provider.json.JsonMapObjectReaderWriter;
import org.apache.cxf.rs.security.jose.jwe.JweDecryptionProvider;
import org.apache.cxf.rs.security.jose.jwe.JweEncryptionProvider;
import org.apache.cxf.rs.security.jose.jwe.JweUtils;
import org.apache.cxf.rs.security.jose.jws.JwsCompactConsumer;
import org.apache.cxf.rs.security.jose.jws.JwsCompactProducer;
import org.apache.cxf.rs.security.jose.jws.JwsSignatureProvider;
import org.apache.cxf.rs.security.jose.jws.JwsSignatureVerifier;
import org.apache.cxf.rs.security.jose.jws.JwsUtils;
import org.apache.cxf.rs.security.jose.jws.NoneJwsSignatureProvider;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;

public class JoseClientCodeStateManager implements ClientCodeStateManager {
    
    private JwsSignatureProvider sigProvider;
    private JweEncryptionProvider encryptionProvider;
    private JweDecryptionProvider decryptionProvider;
    private JwsSignatureVerifier signatureVerifier;
    private JsonMapObjectReaderWriter jsonp = new JsonMapObjectReaderWriter();
    @Override
    public MultivaluedMap<String, String> toRedirectState(MessageContext mc, 
                                                          MultivaluedMap<String, String> requestState) {
        
        Map<String, Object> stateMap = CastUtils.cast((Map<?, ?>)requestState);
        String json = jsonp.toJson(stateMap);
        
        JwsCompactProducer producer = new JwsCompactProducer(json);
        JwsSignatureProvider theSigProvider = getInitializedSigProvider();
        String stateParam = producer.signWith(theSigProvider);
        
        JweEncryptionProvider theEncryptionProvider = getInitializedEncryptionProvider();
        if (theEncryptionProvider != null) {
            stateParam = theEncryptionProvider.encrypt(StringUtils.toBytesUTF8(stateParam), null);
        }
        MultivaluedMap<String, String> map = new MetadataMap<String, String>();
        map.putSingle(OAuthConstants.STATE, stateParam);
        return map;
    }

    @Override
    public MultivaluedMap<String, String> fromRedirectState(MessageContext mc, 
                                                            MultivaluedMap<String, String> redirectState) {
        
        String stateParam = redirectState.getFirst(OAuthConstants.STATE);
        
        JweDecryptionProvider jwe = getInitializedDecryptionProvider();
        if (jwe != null) {
            stateParam = jwe.decrypt(stateParam).getContentText();
        }
        JwsCompactConsumer jws = new JwsCompactConsumer(stateParam);
        JwsSignatureVerifier theSigVerifier = getInitializedSigVerifier();
        if (!jws.verifySignatureWith(theSigVerifier)) {
            throw new SecurityException();
        }
        String json = jws.getUnsignedEncodedSequence();
        Map<String, List<String>> map = CastUtils.cast((Map<?, ?>)jsonp.fromJson(json));
        //CHECKSTYLE:OFF
        return (MultivaluedMap<String, String>)map;
        //CHECKSTYLE:ON
    }
    
    public void setSignatureProvider(JwsSignatureProvider signatureProvider) {
        this.sigProvider = signatureProvider;
    }
    
    protected JwsSignatureProvider getInitializedSigProvider() {
        if (sigProvider != null) {
            return sigProvider;    
        } 
        JwsSignatureProvider theSigProvider = JwsUtils.loadSignatureProvider(false);
        if (theSigProvider == null) {
            theSigProvider = new NoneJwsSignatureProvider();
        }
        return theSigProvider;
    }
    public void setDecryptionProvider(JweDecryptionProvider decProvider) {
        this.decryptionProvider = decProvider;
    }
    protected JweDecryptionProvider getInitializedDecryptionProvider() {
        if (decryptionProvider != null) {
            return decryptionProvider;    
        } 
        return JweUtils.loadDecryptionProvider(false);
    }
    public void setSignatureVerifier(JwsSignatureVerifier signatureVerifier) {
        this.signatureVerifier = signatureVerifier;
    }
    
    protected JwsSignatureVerifier getInitializedSigVerifier() {
        if (signatureVerifier != null) {
            return signatureVerifier;    
        } 
        return JwsUtils.loadSignatureVerifier(false);
    }
    public void setEncryptionProvider(JweEncryptionProvider encProvider) {
        this.encryptionProvider = encProvider;
    }
    protected JweEncryptionProvider getInitializedEncryptionProvider() {
        if (encryptionProvider != null) {
            return encryptionProvider;    
        } 
        return JweUtils.loadEncryptionProvider(false);
    }
    
}
