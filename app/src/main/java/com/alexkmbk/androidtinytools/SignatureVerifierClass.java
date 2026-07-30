package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;

public class SignatureVerifierClass {
    public SignatureVerifierClass(Activity context) {
    }

    public boolean verifyRsaPssSha256Signature(String publicKeyPem, String message, String signatureBase64) {
        if (publicKeyPem == null || message == null || signatureBase64 == null) {
            return false;
        }

        try {
            PublicKey publicKey = loadPublicKey(publicKeyPem);
            if (!(publicKey instanceof RSAPublicKey)) {
                return false;
            }

            byte[] signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            int saltLength = getMaxPssSaltLength((RSAPublicKey) publicKey);
            if (saltLength < 0) {
                return false;
            }

            Signature verifier = Signature.getInstance("RSASSA-PSS");
            verifier.setParameter(new PSSParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    saltLength,
                    1
            ));
            verifier.initVerify(publicKey);
            verifier.update(messageBytes);

            return verifier.verify(signatureBytes);
        } catch (Exception exception) {
            return false;
        }
    }

    private PublicKey loadPublicKey(String publicKeyPem) throws Exception {
        String publicKeyBase64 = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);

        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    private int getMaxPssSaltLength(RSAPublicKey publicKey) {
        int hashLength = 32;
        int emBits = publicKey.getModulus().bitLength() - 1;
        int emLength = (emBits + 7) / 8;

        return emLength - hashLength - 2;
    }
}
