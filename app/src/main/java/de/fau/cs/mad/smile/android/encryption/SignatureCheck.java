package de.fau.cs.mad.smile.android.encryption;

import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;

import org.spongycastle.asn1.ASN1Encoding;
import org.spongycastle.asn1.ASN1InputStream;
import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.ASN1OctetString;
import org.spongycastle.asn1.ASN1Primitive;
import org.spongycastle.asn1.DEROctetString;
import org.spongycastle.asn1.cms.Attribute;
import org.spongycastle.asn1.cms.AttributeTable;
import org.spongycastle.asn1.cms.CMSAttributes;
import org.spongycastle.asn1.cms.Time;
import org.spongycastle.asn1.x509.AuthorityKeyIdentifier;
import org.spongycastle.asn1.x509.ExtendedKeyUsage;
import org.spongycastle.asn1.x509.Extension;
import org.spongycastle.asn1.x509.KeyPurposeId;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.jcajce.JcaCertStoreBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.cms.CMSException;
import org.spongycastle.cms.CMSProcessable;
import org.spongycastle.cms.CMSProcessableByteArray;
import org.spongycastle.cms.CMSSignedData;
import org.spongycastle.cms.CMSSignedDataParser;
import org.spongycastle.cms.CMSTypedStream;
import org.spongycastle.cms.SignerId;
import org.spongycastle.cms.SignerInformation;
import org.spongycastle.cms.SignerInformationStore;
import org.spongycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.spongycastle.cms.jcajce.JcaX509CertSelectorConverter;
import org.spongycastle.i18n.ErrorBundle;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.mail.smime.SMIMEException;
import org.spongycastle.mail.smime.SMIMESigned;
import org.spongycastle.mail.smime.SMIMESignedParser;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.bc.BcDigestCalculatorProvider;
import org.spongycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.spongycastle.util.Store;
import org.spongycastle.x509.CertPathReviewerException;
import org.spongycastle.x509.PKIXCertPathReviewer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertPath;
import java.security.cert.CertStore;
import java.security.cert.CertStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.SharedByteArrayInputStream;

public class SignatureCheck {
    static {
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    private static final int SHORT_KEY_LENGTH = 512;

    private final KeyManagement keyManagement;

    public SignatureCheck() throws CertificateException, NoSuchAlgorithmException, KeyStoreException, IOException {
        keyManagement = new KeyManagement();
    }

    public Boolean verifySignature(final MimeBodyPart bodyPart, final String sender)
            throws MessagingException, CMSException, SMIMEException, IOException,
            GeneralSecurityException, OperatorCreationException, CertPathReviewerException {
        if (bodyPart == null) {
            return false;
        }

        boolean valid = true;

        SMIMESigned signed = new SMIMESigned((MimeMultipart) bodyPart.getContent());

        JcaCertStoreBuilder jcaCertStoreBuilder = new JcaCertStoreBuilder();
        jcaCertStoreBuilder.addCertificates(signed.getCertificates());
        jcaCertStoreBuilder.setProvider(BouncyCastleProvider.PROVIDER_NAME);

        CertStore certs = jcaCertStoreBuilder.build();
        SignerInformationStore signers = signed.getSignerInfos();

        KeyStore keyStore = KeyStore.getInstance("AndroidCAStore");
        keyStore.load(null);
        PKIXParameters usedParameters = new PKIXParameters(keyStore);
        usedParameters.addCertStore(certs);

        Collection signersCollection = signers.getSigners();
        Iterator iterator = signersCollection.iterator();

        while (iterator.hasNext()) {
            SignerInformation signer = (SignerInformation) iterator.next();
            List<X509Certificate> certCollection = findCerts(usedParameters.getCertStores(), signer.getSID());
            for (X509Certificate cert : certCollection) {
                // check signature
                boolean validSignature = signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert.getPublicKey()));
                valid |= validSignature;
                valid |= checkSigner(cert, sender);
                Date signTime = getSignatureTime(signer);

                if (signTime == null) {
                    // TODO: notify no signing time
                    signTime = usedParameters.getDate();
                    if (signTime == null) {
                        signTime = new Date();
                    }
                } else {
                    cert.checkValidity(signTime);
                }

                usedParameters.setDate(signTime);
                Object[] certPathUserProvided = createCertPath(cert, usedParameters.getTrustAnchors(), usedParameters.getCertStores(), Collections.singletonList(certs));
                CertPath certPath = (CertPath) certPathUserProvided[0];

                PKIXCertPathReviewer review = new PKIXCertPathReviewer(certPath, usedParameters);
                valid |= review.isValidCertPath();
            }
        }

        return valid;
    }

    /**
     * Taken from bouncycastle SignedMailValidator
     * Returns an Object array containing a CertPath and a List of Booleans. The list contains the value <code>true</code>
     * if the corresponding certificate in the CertPath was taken from the user provided CertStores.
     *
     * @param signerCert       the end of the path
     * @param trustAnchors     trust anchors for the path
     * @param systemCertStores list of {@link CertStore} provided by the system
     * @param userCertStores   list of {@link CertStore} provided by the user
     * @return a CertPath and a List of booleans.
     * @throws GeneralSecurityException
     */
    public Object[] createCertPath(X509Certificate signerCert,
                                   Set<TrustAnchor> trustAnchors, List<CertStore> systemCertStores, List<CertStore> userCertStores)
            throws GeneralSecurityException {
        Set<X509Certificate> certSet = new LinkedHashSet<>();
        List<Boolean> userProvidedList = new ArrayList<>();

        // add signer certificate

        X509Certificate cert = signerCert;
        certSet.add(cert);
        userProvidedList.add(true);

        boolean trustAnchorFound = false;

        X509Certificate trustAnchorCert = null;

        // add other certs to the cert path
        while (cert != null && !trustAnchorFound) {
            // check if cert Issuer is Trustanchor
            for (TrustAnchor anchor : trustAnchors) {
                X509Certificate anchorCert = anchor.getTrustedCert();
                if (anchorCert != null) {
                    if (anchorCert.getSubjectX500Principal().equals(
                            cert.getIssuerX500Principal())) {
                        try {
                            cert.verify(anchorCert.getPublicKey(), "BC");
                            trustAnchorFound = true;
                            trustAnchorCert = anchorCert;
                            break;
                        } catch (Exception e) {
                            // trustanchor not found
                        }
                    }
                } else {
                    if (anchor.getCAName().equals(
                            cert.getIssuerX500Principal().getName())) {
                        try {
                            cert.verify(anchor.getCAPublicKey(), "BC");
                            trustAnchorFound = true;
                            break;
                        } catch (Exception e) {
                            // trustanchor not found
                        }
                    }
                }
            }

            if (!trustAnchorFound) {
                // add next cert to path
                X509CertSelector select = new X509CertSelector();
                try {
                    select.setSubject(cert.getIssuerX500Principal().getEncoded());
                } catch (IOException e) {
                    throw new IllegalStateException(e.toString());
                }
                byte[] authKeyIdentBytes = cert.getExtensionValue(Extension.authorityKeyIdentifier.getId());
                if (authKeyIdentBytes != null) {
                    try {
                        AuthorityKeyIdentifier kid = AuthorityKeyIdentifier.getInstance(getObject(authKeyIdentBytes));
                        if (kid.getKeyIdentifier() != null) {
                            select.setSubjectKeyIdentifier(new DEROctetString(kid.getKeyIdentifier()).getEncoded(ASN1Encoding.DER));
                        }
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
                boolean userProvided = false;

                cert = findNextCert(systemCertStores, select, certSet);
                if (cert == null && userCertStores != null) {
                    userProvided = true;
                    cert = findNextCert(userCertStores, select, certSet);
                }

                if (cert != null) {
                    // cert found
                    certSet.add(cert);
                    userProvidedList.add(userProvided);
                }
            }
        }

        // if a trustanchor was found - try to find a selfsigned certificate of
        // the trustanchor
        if (trustAnchorFound) {
            if (trustAnchorCert != null && trustAnchorCert.getSubjectX500Principal().equals(trustAnchorCert.getIssuerX500Principal())) {
                certSet.add(trustAnchorCert);
                userProvidedList.add(false);
            } else {
                X509CertSelector select = new X509CertSelector();

                try {
                    select.setSubject(cert.getIssuerX500Principal().getEncoded());
                    select.setIssuer(cert.getIssuerX500Principal().getEncoded());
                } catch (IOException e) {
                    throw new IllegalStateException(e.toString());
                }

                boolean userProvided = false;

                trustAnchorCert = findNextCert(systemCertStores, select, certSet);
                if (trustAnchorCert == null && userCertStores != null) {
                    userProvided = true;
                    trustAnchorCert = findNextCert(userCertStores, select, certSet);
                }

                if (trustAnchorCert != null) {
                    try {
                        cert.verify(trustAnchorCert.getPublicKey(), "BC");
                        certSet.add(trustAnchorCert);
                        userProvidedList.add(userProvided);
                    } catch (GeneralSecurityException gse) {
                        // wrong cert
                    }
                }
            }
        }

        CertPath certPath = CertificateFactory.getInstance("X.509", "BC").generateCertPath(new ArrayList<>(certSet));
        return new Object[]{certPath, userProvidedList};
    }

    private X509Certificate findNextCert(List<CertStore> certStores, X509CertSelector selector, Set<X509Certificate> certSet)
            throws CertStoreException {
        List<X509Certificate> certificates = findCerts(certStores, selector);

        for (X509Certificate certificate : certificates) {
            if (!certSet.contains(certificate)) {
                return certificate;
            }
        }

        return null;
    }

    private boolean checkSigner(final X509Certificate cert, final String sender) throws CertificateParsingException, CertificateEncodingException, IOException {
        boolean valid = true;
        valid |= checkKeyLength(cert);
        valid |= checkKeyUsage(cert);
        valid |= checkExtendedKeyUsage(cert);
        valid |= checkMailAddresses(cert, sender);
        return valid;
    }

    private boolean checkMailAddresses(X509Certificate cert, String sender) throws CertificateParsingException, CertificateEncodingException {
        List<String> names = keyManagement.getAlternateNamesFromCert(cert);
        return names.contains(sender);
    }

    private boolean checkExtendedKeyUsage(X509Certificate cert) throws CertificateParsingException {

        List<String> extendedKeyUsage = cert.getExtendedKeyUsage();
        return extendedKeyUsage.contains(KeyPurposeId.anyExtendedKeyUsage.getId()) ||
                extendedKeyUsage.contains(KeyPurposeId.id_kp_emailProtection.getId());
    }

    private boolean checkKeyLength(X509Certificate cert) {
        PublicKey key = cert.getPublicKey();
        int keyLength = -1;
        if (key instanceof RSAPublicKey) {
            keyLength = ((RSAPublicKey) key).getModulus().bitLength();
        } else if (key instanceof DSAPublicKey) {
            keyLength = ((DSAPublicKey) key).getParams().getP().bitLength();
        }

        return (keyLength != -1 && keyLength <= SHORT_KEY_LENGTH);
    }

    /**
     * See https://tools.ietf.org/html/rfc5280#section-4.2.1.3
     *
     * @param cert the certificate to check key usage for
     * @return true if key usage is set and either digitalSignature or nonRepudiation are present, otherwise false
     */
    private boolean checkKeyUsage(X509Certificate cert) {
        boolean[] keyUsage = cert.getKeyUsage();
        if (keyUsage == null) {
            return false;
        }

        return keyUsage[0] || keyUsage[1];
    }

    private Date getSignatureTime(SignerInformation signer) {
        AttributeTable attributeTable = signer.getSignedAttributes();
        Date result = null;

        if (attributeTable != null) {
            Attribute attr = attributeTable.get(CMSAttributes.signingTime);
            if (attr != null) {
                Time t = Time.getInstance(attr.getAttrValues().getObjectAt(0)
                        .toASN1Primitive());
                result = t.getDate();
            }
        }

        return result;
    }

    private ASN1Primitive getObject(byte[] ext)
            throws IOException {
        ASN1InputStream aIn = new ASN1InputStream(ext);
        ASN1OctetString octs = (ASN1OctetString) aIn.readObject();

        aIn = new ASN1InputStream(octs.getOctets());
        return aIn.readObject();
    }

    private List<X509Certificate> findCerts(List<CertStore> certStores, SignerId sid) throws CertStoreException {
        JcaX509CertSelectorConverter converter = new JcaX509CertSelectorConverter();
        return findCerts(certStores, converter.getCertSelector(sid));
    }

    private List<X509Certificate> findCerts(List<CertStore> certStores, X509CertSelector selector) throws CertStoreException {
        List<X509Certificate> certificates = new ArrayList<>();
        for (CertStore certStore : certStores) {
            Collection<? extends Certificate> storeCerts = certStore.getCertificates(selector);
            for (Certificate cert : storeCerts) {
                if (cert.getType().equals("X.509")) {
                    certificates.add((X509Certificate) cert);
                }
            }
        }

        return certificates;
    }

    public Boolean checkSignature(String pathToFile) {
        try {
            Log.d(SMileCrypto.LOG_TAG, "Check signature for file: " + pathToFile);
            Properties props = System.getProperties();
            Session session = Session.getDefaultInstance(props, null);
            File file = new File(pathToFile);
            MimeMessage mimeMessage = new MimeMessage(session, new FileInputStream(file));
            Log.d(SMileCrypto.LOG_TAG, mimeMessage.getContentType());
            Log.d(SMileCrypto.LOG_TAG, mimeMessage.getContent().toString());
            return new AsyncCheckSignature().execute(mimeMessage).get();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Exception while converting file to MimeMessage: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public Boolean checkSignature(MimeMessage mimeMessage) {
        SMIMEToolkit toolkit = new SMIMEToolkit(new BcDigestCalculatorProvider());
        try {
            Log.e(SMileCrypto.LOG_TAG, "----------------\ntry working example");
            workingExample();
            Log.e(SMileCrypto.LOG_TAG, "finished working example\n----------------");

            if (toolkit.isSigned(mimeMessage))
                Log.d(SMileCrypto.LOG_TAG, "MimeMessage is signed!");
            else {
                Log.d(SMileCrypto.LOG_TAG, "MimeMessage is NOT signed!");
                return false;
            }

            anotherSignatureCheck(mimeMessage); //does not work :-(

            byte[] signatureWithCert = ("MIAGCSqGSIb3DQEHAqCAMIACAQExCzAJBgUrDgMCGgUAMIAGCSqGSIb3DQEHAQAAoIAwggPNMIIC" +
                    "taADAgECAgkAq46Vk5EJgW4wDQYJKoZIhvcNAQELBQAwfTELMAkGA1UEBhMCREUxCzAJBgNVBAgM" +
                    "AkJZMREwDwYDVQQHDAhFcmxhbmdlbjESMBAGA1UECgwJTUFELUZpeG1lMRQwEgYDVQQDDAtGaXgg" +
                    "TXkgTWFpbDEkMCIGCSqGSIb3DQEJARYVZml4bXltYWlsQHQtb25saW5lLmRlMB4XDTE1MDQyODA4" +
                    "MjkyNloXDTE2MDQyNzA4MjkyNlowfTELMAkGA1UEBhMCREUxCzAJBgNVBAgMAkJZMREwDwYDVQQH" +
                    "DAhFcmxhbmdlbjESMBAGA1UECgwJTUFELUZpeG1lMRQwEgYDVQQDDAtGaXggTXkgTWFpbDEkMCIG" +
                    "CSqGSIb3DQEJARYVZml4bXltYWlsQHQtb25saW5lLmRlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A" +
                    "MIIBCgKCAQEAwrN63G5RzZXerqI/V+Qvapu6yBA4TuiGOhMPCqYm7//5/J6+jiLrvEJizsNCiiEG" +
                    "h9bn8Nqw94F/o7YOqDJCkInZyRh0Jw8hBMEtDXmiEcD2W2D2r9/g+0pa6G683WpQQPnhbSIc52yg" +
                    "Pxipd3NaQ8LD/RHGIj+AH2eF6+evabc167yi/NYvcKunWFVQciN1L5fCF5NsVxNstkPPGQgeuzPX" +
                    "mcDjAATrltFT2Re3FWgzZmERYuVEasj2/PdwX4RvwS009d2s/8e7EGSOnK2o5FDo7CO6yAKWPX4m" +
                    "p/W4PCj4861fWsOx4rZic6NP4VAge2/lwVYNeOlAdmgJMOzR7QIDAQABo1AwTjAdBgNVHQ4EFgQU" +
                    "/v99mI/RcXeNUfFdtOrUAQIRYJIwHwYDVR0jBBgwFoAU/v99mI/RcXeNUfFdtOrUAQIRYJIwDAYD" +
                    "VR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAbP3/IPTDfHAh42QAtQkbb2ed2BAHjEb2xTUD" +
                    "QZMnre08d/raXH74zvf5lEFGru98ggUrQsS/zMLkqAqEO7xNkjUyyjKS2y7uFMUv80EVD9tr4E2Z" +
                    "PiP/NqQ4nLIEGhJExZipzRauTdDgjUrV8O8YN+uy3rtrOYcRIcadzWhdnTwU1Q2KhvBTtURSue+v" +
                    "Mkjos8gzwEnTaxoseWsoj68Z6hL65BJU2cg5bYblvLehHlrtznwCt3fzV8vMyA9HkdXVjlpiVfih" +
                    "4mwgWR2sUtobinpMIPaVRuqbM56DFxQfw8MLe/xQ/6QxXUeya0Py9kfU4q26nWGzViQjdeQPSM8S" +
                    "DwAAMYICETCCAg0CAQEwgYowfTELMAkGA1UEBhMCREUxCzAJBgNVBAgMAkJZMREwDwYDVQQHDAhF" +
                    "cmxhbmdlbjESMBAGA1UECgwJTUFELUZpeG1lMRQwEgYDVQQDDAtGaXggTXkgTWFpbDEkMCIGCSqG" +
                    "SIb3DQEJARYVZml4bXltYWlsQHQtb25saW5lLmRlAgkAq46Vk5EJgW4wCQYFKw4DAhoFAKBdMBgG" +
                    "CSqGSIb3DQEJAzELBgkqhkiG9w0BBwEwHAYJKoZIhvcNAQkFMQ8XDTE1MDgxMTE5MzY0OFowIwYJ" +
                    "KoZIhvcNAQkEMRYEFMAgEOsfsAuBZ5HAovClnUx0CxOMMA0GCSqGSIb3DQEBAQUABIIBAF+0GPnX" +
                    "WI3t70Ur0olEzUE3o3xwMhd2iM+eR/y8DYWbQWTsUXRk9UOiuDa023A8ovAcqcESwQb8HFRFZ/g5" +
                    "IPEt3xGbvBpODKi4okkxXB2L7Vfavt61tSL8ehO0L63UdoT2+w7wYzlqPUa+N+amZnYDDf8GdmSE" +
                    "eGOXzdJwYkF2IQUhUR+5i5Bl6GQ9Xlz+ckYb+plaSQ6w2WeZR/ASxB1lsF8HwK06KI/q3ATP6uDE" +
                    "F8MiPJFilbOSeJN/Y+IdvFKdD0XeBL1ddiYXYd24zh0JaWdkx3RdKCQmm2fUw5AycRV6l1vqDjM/" +
                    "UxG+BmJ32O4M45bbocfIbBYbU+mXiTUAAAAAAAA=").getBytes();
            byte[] bPlainText = ("ClRoaXMgbWFpbCAgaGFzIGEgc2lnbmF0dXJlLCBwbGVhc2UgdmVyaWZ5Li4uLgoKU2VudCBmcm9t" +
                    "IG15IGFuZHJvaWQgZGV2aWNlLg==").getBytes(); //TODO.. find correct input...

            CMSProcessable cmsProcessableContent = new CMSProcessableByteArray(Base64.decode(bPlainText, 0));
            CMSSignedData signedData = new CMSSignedData(cmsProcessableContent, Base64.decode(signatureWithCert, 0));

            // Extract Certificate + Verify signature
            Store store = signedData.getCertificates();
            SignerInformationStore signers = signedData.getSignerInfos();
            Collection c = signers.getSigners();
            Iterator it = c.iterator();
            Boolean hasValidSigner = false;
            while (it.hasNext()) {
                SignerInformation signer = (SignerInformation) it.next();
                Collection certCollection = store.getMatches(signer.getSID());
                Iterator certIt = certCollection.iterator();
                X509CertificateHolder certHolder = (X509CertificateHolder) certIt.next();
                X509Certificate certFromSignedData = new JcaX509CertificateConverter().setProvider("SC").getCertificate(certHolder);
                Log.d(SMileCrypto.LOG_TAG, "Info from extracted cert: " + certFromSignedData.getSubjectDN().getName());
                KeyManagement.addFriendsCertificate(certFromSignedData);
                Log.d(SMileCrypto.LOG_TAG, "Check signature now…");
                try {
                    if (signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("SC").build(certFromSignedData))) {
                        Log.d(SMileCrypto.LOG_TAG, "Signature verified!");
                        hasValidSigner = true;
                    } else {
                        Log.d(SMileCrypto.LOG_TAG, "Signature verification failed!");
                    }
                } catch (Exception e) {
                    Log.e(SMileCrypto.LOG_TAG, "Error: " + e.getMessage());
                    Log.e(SMileCrypto.LOG_TAG, "Signature verification failed!");
                }
            }
            return hasValidSigner;
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Exception while checking signature: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private Boolean workingExample() throws Exception {
        Log.e(SMileCrypto.LOG_TAG, "try hardcoded part......");
            /*
            * example from https://stackoverflow.com/questions/16662408/correct-way-to-sign-and-verify-signature-using-bouncycastle
            * */

        // envelopedData == signature (containing cert etc.)
        String envelopedData = "MIAGCSqGSIb3DQEHAqCAMIACAQExCzAJBgUrDgMCGgUAMIAGCSqGSIb3DQEHAQAAoIAwggLQMIIC" +
                "OQIEQ479uzANBgkqhkiG9w0BAQUFADCBrjEmMCQGCSqGSIb3DQEJARYXcm9zZXR0YW5ldEBtZW5k" +
                "ZWxzb24uZGUxCzAJBgNVBAYTAkRFMQ8wDQYDVQQIEwZCZXJsaW4xDzANBgNVBAcTBkJlcmxpbjEi" +
                "MCAGA1UEChMZbWVuZGVsc29uLWUtY29tbWVyY2UgR21iSDEiMCAGA1UECxMZbWVuZGVsc29uLWUt" +
                "Y29tbWVyY2UgR21iSDENMAsGA1UEAxMEbWVuZDAeFw0wNTEyMDExMzQyMTlaFw0xOTA4MTAxMzQy" +
                "MTlaMIGuMSYwJAYJKoZIhvcNAQkBFhdyb3NldHRhbmV0QG1lbmRlbHNvbi5kZTELMAkGA1UEBhMC" +
                "REUxDzANBgNVBAgTBkJlcmxpbjEPMA0GA1UEBxMGQmVybGluMSIwIAYDVQQKExltZW5kZWxzb24t" +
                "ZS1jb21tZXJjZSBHbWJIMSIwIAYDVQQLExltZW5kZWxzb24tZS1jb21tZXJjZSBHbWJIMQ0wCwYD" +
                "VQQDEwRtZW5kMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC+X1g6JvbdwJI6mQMNT41GcycH" +
                "UbwCFWKJ4qHDaHffz3n4h+uQJJoQvc8yLTCfnl109GB0yL2Y5YQtTohOS9IwyyMWBhh77WJtCN8r" +
                "dOfD2DW17877te+NlpugRvg6eOH6np9Vn3RZODVxxTyyJ8pI8VMnn13YeyMMw7VVaEO5hQIDAQAB" +
                "MA0GCSqGSIb3DQEBBQUAA4GBALwOIc/rWMAANdEh/GgO/DSkVMwxM5UBr3TkYbLU/5jg0Lwj3Y++" +
                "KhumYSrxnYewSLqK+JXA4Os9NJ+b3eZRZnnYQ9eKeUZgdE/QP9XE04y8WL6ZHLB4sDnmsgVaTU+p" +
                "0lFyH0Te9NyPBG0J88109CXKdXCTSN5gq0S1CfYn0staAAAxggG9MIIBuQIBATCBtzCBrjEmMCQG" +
                "CSqGSIb3DQEJARYXcm9zZXR0YW5ldEBtZW5kZWxzb24uZGUxCzAJBgNVBAYTAkRFMQ8wDQYDVQQI" +
                "EwZCZXJsaW4xDzANBgNVBAcTBkJlcmxpbjEiMCAGA1UEChMZbWVuZGVsc29uLWUtY29tbWVyY2Ug" +
                "R21iSDEiMCAGA1UECxMZbWVuZGVsc29uLWUtY29tbWVyY2UgR21iSDENMAsGA1UEAxMEbWVuZAIE" +
                "Q479uzAJBgUrDgMCGgUAoF0wGAYJKoZIhvcNAQkDMQsGCSqGSIb3DQEHATAcBgkqhkiG9w0BCQUx" +
                "DxcNMTMwNTIxMDE1MDUzWjAjBgkqhkiG9w0BCQQxFgQU8mE6gw6iudxLUc9379lWK0lUSWcwDQYJ" +
                "KoZIhvcNAQEBBQAEgYB5mVhqJu1iX9nUqfqk7hTYJb1lR/hQiCaxruEuInkuVTglYuyzivZjAR54" +
                "zx7Cfm5lkcRyyxQ35ztqoq/V5JzBa+dYkisKcHGptJX3CbmmDIa1s65mEye4eLS4MTBvXCNCUTb9" +
                "STYSWvr4VPenN80mbpqSS6JpVxjM0gF3QTAhHwAAAAAAAA==";
        String Sig_Bytes = "YduK22AlMLSXV3ajX5r/pX5OQ0xjj58uhGT9I9MvOrz912xNHo+9OiOKeMOD+Ys2/LUW3XaN6T+/" +
                "tuRM5bi4RK7yjaqaJCZWtr/O4I968BQGgt0cyNvK8u0Jagbr9MYk6G7nnejbRXYHyAOaunqD05lW" +
                "U/+g92i18dl0OMc50m4=";

        //Log.e(SMileCrypto.LOG_TAG, "Sig_Bytes = " + new String(Base64.decode(Sig_Bytes, 0)));
        //Log.e(SMileCrypto.LOG_TAG, "envelopedData = " + new String(Base64.decode(envelopedData, 0)));

        CMSProcessable cmsProcessableContent = new CMSProcessableByteArray(Base64.decode(Sig_Bytes.getBytes(), 0));
        CMSSignedData signedData = new CMSSignedData(cmsProcessableContent, Base64.decode(envelopedData.getBytes(), 0));

        // Extract Certificate + Verify signature
        Store store = signedData.getCertificates();
        SignerInformationStore signers = signedData.getSignerInfos();
        Collection c = signers.getSigners();
        Iterator it = c.iterator();
        Boolean hasValidSigner = false;
        while (it.hasNext()) {
            SignerInformation signer = (SignerInformation) it.next();
            Collection certCollection = store.getMatches(signer.getSID());
            Iterator certIt = certCollection.iterator();
            X509CertificateHolder certHolder = (X509CertificateHolder) certIt.next();
            X509Certificate certFromSignedData = new JcaX509CertificateConverter().setProvider("SC").getCertificate(certHolder);
            KeyManagement.addFriendsCertificate(certFromSignedData);
            Log.d(SMileCrypto.LOG_TAG, "Info from extracted cert: " + certFromSignedData.getSubjectDN().getName());
            Log.d(SMileCrypto.LOG_TAG, "Check signature now…");
            try {
                if (signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("SC").build(certFromSignedData))) {
                    Log.d(SMileCrypto.LOG_TAG, "Signature verified!");
                    hasValidSigner = true;
                } else {
                    Log.d(SMileCrypto.LOG_TAG, "Signature verification failed!");
                }
            } catch (Exception e) {
                Log.e(SMileCrypto.LOG_TAG, "Error: " + e.getMessage());
                Log.e(SMileCrypto.LOG_TAG, "Signature verification failed!");
            }
        }
        //verifySignature(Base64.decode(envelopedData, 0), Base64.decode(Sig_Bytes, 0)); //works!

        return hasValidSigner;
    }

    private Boolean anotherSignatureCheck(MimeMessage mimeMessage) throws Exception {
        Object part = mimeMessage.getContent();
        Log.e(SMileCrypto.LOG_TAG, "instance of: " + part.toString());
        if (part instanceof SharedByteArrayInputStream) {
            String content = getUTF8Content(part);
            Log.e(SMileCrypto.LOG_TAG, "content: " + content);
            DecryptMail dM = new DecryptMail();
            mimeMessage = dM.decodeMimeBodyParts(content, false, mimeMessage.getContentType().replace("multipart/", ""));
            Log.d(SMileCrypto.LOG_TAG, mimeMessage.getContentType());
            MimeMultipart part2 = (MimeMultipart) mimeMessage.getContent();
            int count = part2.getCount();
            for (int i = 0; i < count; i++) {
                BodyPart bodyPart = part2.getBodyPart(i);
                Log.e(SMileCrypto.LOG_TAG, bodyPart.toString());
                Log.e(SMileCrypto.LOG_TAG, bodyPart.getContentType());
                Log.e(SMileCrypto.LOG_TAG, bodyPart.getContent().toString());
            }

            SMIMEToolkit toolkit = new SMIMEToolkit(new BcDigestCalculatorProvider());
            if (toolkit.isSigned(mimeMessage)) {
                Log.d(SMileCrypto.LOG_TAG, "MimeMessage is signed!");
            } else {
                Log.d(SMileCrypto.LOG_TAG, "MimeMessage is NOT signed?");
            }
        }

        SMIMESignedParser s = new SMIMESignedParser(new JcaDigestCalculatorProviderBuilder().build(), mimeMessage);

        Store certs = s.getCertificates();
        //
        // SignerInfo blocks which contain the signatures
        //
        SignerInformationStore signers = s.getSignerInfos();

        Collection c = signers.getSigners();
        Iterator it = c.iterator();

        //
        // check each signer
        //
        while (it.hasNext()) {
            SignerInformation signer = (SignerInformation) it.next();
            Collection certCollection = certs.getMatches(signer.getSID());

            Iterator certIt = certCollection.iterator();
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("SC").getCertificate((X509CertificateHolder) certIt.next());
            //
            // verify that the sig is correct and that it was generated
            // when the certificate was current
            //
            if (signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("SC").build(cert))) {
                Log.e(SMileCrypto.LOG_TAG, "Signature verified.");
            } else {
                Log.e(SMileCrypto.LOG_TAG, "Signature failed.");
            }
        }

        return false;
    }

    private static String getUTF8Content(Object contentObject) throws Exception {
        // possible ClassCastException
        SharedByteArrayInputStream sbais = (SharedByteArrayInputStream) contentObject;
        // possible UnsupportedEncodingException
        InputStreamReader isr = new InputStreamReader(sbais, Charset.forName("UTF-8"));
        int charsRead = 0;
        StringBuilder content = new StringBuilder();
        int bufferSize = 1024;
        char[] buffer = new char[bufferSize];
        // possible IOException
        while ((charsRead = isr.read(buffer)) != -1) {
            content.append(Arrays.copyOf(buffer, charsRead));
        }
        return content.toString();
    }

    public void verifySignature(byte[] signedData, byte[] bPlainText) throws Exception {
        InputStream is = new ByteArrayInputStream(bPlainText);
        CMSSignedDataParser sp = new CMSSignedDataParser(new BcDigestCalculatorProvider(), new CMSTypedStream(is), signedData);
        CMSTypedStream signedContent = sp.getSignedContent();

        signedContent.drain();

        //CMSSignedData s = new CMSSignedData(signedData);
        Store certStore = sp.getCertificates();

        SignerInformationStore signers = sp.getSignerInfos();
        Collection c = signers.getSigners();
        Iterator it = c.iterator();
        while (it.hasNext()) {
            SignerInformation signer = (SignerInformation) it.next();
            Collection certCollection = certStore.getMatches(signer.getSID());
            Iterator certIt = certCollection.iterator();
            X509CertificateHolder certHolder = (X509CertificateHolder) certIt.next();

            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("SC").build(certHolder))) {
                Log.e(SMileCrypto.LOG_TAG, "FAIL!");
            } else {
                Log.e(SMileCrypto.LOG_TAG, "SUCCESS!");
            }
        }
    }

    private class AsyncCheckSignature extends AsyncTask<Object, Void, Boolean> {

        protected Boolean doInBackground(Object... params) {
            return checkSignature((MimeMessage) params[0]);
        }
    }
}