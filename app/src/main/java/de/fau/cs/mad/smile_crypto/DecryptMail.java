package de.fau.cs.mad.smile_crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.sun.mail.util.QPDecoderStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.spongycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.spongycastle.cms.jcajce.JceKeyTransRecipientId;
import org.spongycastle.mail.smime.SMIMEException;
import org.spongycastle.operator.bc.BcDigestCalculatorProvider;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class DecryptMail {
    static {
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    private final String certificateDirectory;
    private final KeyManagement keyManagement;

    //TODO: parameter for AsyncTask
    private String alias;
    private MimeMessage encryptedMimeMessage;
    private String pathToFile;
    private String passphrase;

    //MimeBodyPart messagePart;

    public DecryptMail() throws KeyStoreException, IOException, NoSuchAlgorithmException,
            CertificateException {
        final Context context = App.getContext();
        this.certificateDirectory = context.getDir(
                context.getString(R.string.smime_certificates_folder), Context.MODE_PRIVATE).
                getAbsolutePath();
        keyManagement = new KeyManagement();
    }

    private MimeBodyPart decryptMailSynchronous(String pathToFile, String passphrase) {
        try {
            MimeMessage mimeMessage = getMimeMessageFromFile(pathToFile);

            Address[] recipients = getMailAddressFromMimeMessage(mimeMessage);
            if (recipients == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_RECIPIENTS_FOUND;
                return null;
            }

            String alias = null;
            for (Address r : recipients) {
                if ((alias = keyManagement.getAliasByAddress(r)) != null) {
                    break;
                }
            }

            if (alias == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_CERTIFICATE_FOUND;
                return null;
            }

            return decryptMailSynchronous(alias, mimeMessage, passphrase);
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error in DecryptMail: " + e.getMessage());
            e.printStackTrace();
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
            return null;
        }
    }

    private MimeBodyPart decryptMailSynchronous(String alias, MimeMessage mimeMessage, String passphrase) {
        try {
            encryptedMimeMessage = mimeMessage;

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

            final PrivateKey privateKey = keyManagement.getPrivateKeyForAlias(alias, passphrase);
            if (privateKey == null) {
                Log.e(SMileCrypto.LOG_TAG, "Could not find private key!");
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_CERTIFICATE_STORED;
                return null;
            }

            SMIMEToolkit toolkit = new SMIMEToolkit(new BcDigestCalculatorProvider());

            MimeBodyPart dec = toolkit.decrypt(mimeMessage, new JceKeyTransRecipientId(cert),
                    new JceKeyTransEnvelopedRecipient(privateKey).setProvider("SC"));

            //Log.d(SMileCrypto.LOG_TAG, "MESSAGE: " + mimeMessage.getContent());
            //Log.d(SMileCrypto.LOG_TAG, "DECRYPT: " + convertMimeBodyPartToString(dec));

            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_SUCCESS;
            return dec;
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error in DecryptMail: " + e.getMessage());
            e.printStackTrace();
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
            return null;
        }
    }

    public MimeBodyPart decryptMail(String pathToFile, String passphrase) {
        this.alias = null;
        this.encryptedMimeMessage = null;
        this.pathToFile = pathToFile;
        this.passphrase = passphrase;

        try {
            return new AsyncDecryptMail().execute().get();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error while waiting for AsyncTask: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_ERROR_ASYNC_TASK;
            return null;
        }
    }

    public final MimeBodyPart decryptMail(final String senderAddress,
                                          final MimeBodyPart mimeBodyPart)
            throws KeyStoreException, MessagingException, NoSuchAlgorithmException,
            CertificateException, IOException, SMIMEException {
        if (mimeBodyPart == null) {
            Log.e(SMileCrypto.LOG_TAG, "Called decryptMail with empty mimeMessage.");
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
            return null;
        }

        this.alias = keyManagement.getAliasByAddress(new InternetAddress(senderAddress));

        Log.d(SMileCrypto.LOG_TAG, "Check whether passphrase is available for alias: " + senderAddress);
        this.passphrase = keyManagement.getPassphraseForAlias(this.alias);
        if (this.passphrase == null) {
            Log.e(SMileCrypto.LOG_TAG, "Called decryptMail without passphrase.");
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_PASSPHRASE_AVAILABLE;
            return null;
        }

        X509Certificate cert = keyManagement.getCertificateForAlias(alias);

        final PrivateKey privateKey = keyManagement.getPrivateKeyForAlias(alias, passphrase);
        if (privateKey == null) {
            Log.e(SMileCrypto.LOG_TAG, "Could not find private key!");
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_CERTIFICATE_STORED;
            return null;
        }

        SMIMEToolkit toolkit = new SMIMEToolkit(new BcDigestCalculatorProvider());

        MimeBodyPart decryptedBodyPart = toolkit.decrypt(mimeBodyPart, new JceKeyTransRecipientId(cert),
                new JceKeyTransEnvelopedRecipient(privateKey).setProvider("SC"));
        return decryptedBodyPart;
    }

    public MimeBodyPart decryptMail(String alias, MimeMessage mimeMessage, String passphrase) {
        if (mimeMessage == null) {
            Log.e(SMileCrypto.LOG_TAG, "Called decryptMail with empty mimeMessage.");
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
            return null;
        }

        if (alias == null) {
            alias = getAliasByMimeMessage(mimeMessage);
            if (alias == null) {
                Log.e(SMileCrypto.LOG_TAG, "Called decryptMail with empty alias.");
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_CERTIFICATE_FOUND;
                return null;
            }
            Log.d(SMileCrypto.LOG_TAG, "Found alias: " + alias);
        }

        if (passphrase == null) {
            Log.d(SMileCrypto.LOG_TAG, "Check whether passphrase is available for alias: " + alias);
            passphrase = keyManagement.getPassphraseForAlias(alias);
            if (passphrase == null) {
                Log.e(SMileCrypto.LOG_TAG, "Called decryptMail without passphrase.");
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_PASSPHRASE_AVAILABLE;
                return null;
            }
        }

        this.alias = alias;
        this.encryptedMimeMessage = mimeMessage;
        this.passphrase = passphrase;

        try {
            return new AsyncDecryptMail().execute().get();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error while waiting for AsyncTask: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_ERROR_ASYNC_TASK;
            return null;
        }
    }

    public MimeBodyPart decryptMail(Address emailAddress, MimeMessage mimeMessage, String passphrase) {
        String alias = keyManagement.getAliasByAddress(emailAddress);
        if (alias == null) {
            Log.e(SMileCrypto.LOG_TAG, "Could not find certificate for given email address in decryptMail.");
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_CERTIFICATE_FOUND;
            return null;
        }

        return decryptMail(alias, mimeMessage, passphrase);
    }

    public MimeMessage decryptEncodeMail(String pathToFile, String passphrase) {
        this.alias = null;
        this.encryptedMimeMessage = null;
        this.pathToFile = pathToFile;
        this.passphrase = passphrase;

        try {
            return new AsyncDecryptEncodeMail().execute().get();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error while waiting for AsyncTask: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_ERROR_ASYNC_TASK;
            return null;
        }
    }

    public MimeMessage getMimeMessageFromFile(String pathToFile) {
        try {
            Properties props = System.getProperties();
            Session session = Session.getDefaultInstance(props, null);
            File file = new File(pathToFile);
            return new MimeMessage(session, new FileInputStream(file));
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Exception while reading encrypted mail: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_VALID_MIMEMESSAGE_IN_FILE;
            return null;
        }
    }

    public String getAliasByMimeMessage(MimeMessage mimeMessage) {
        Address[] addresses = getMailAddressFromMimeMessage(mimeMessage);
        if (addresses == null) {
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_RECIPIENTS_FOUND;
            return null;
        }

        for (Address a : addresses) {
            String alias = keyManagement.getAliasByAddress(a);
            if (alias != null) {
                return alias;
            }

        }

        SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_NO_CERTIFICATE_FOUND;
        return null;
    }

    private Address[] getMailAddressFromMimeMessage(MimeMessage mimeMessage) {
        try {
            return mimeMessage.getAllRecipients();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error while getting all recipients: " + e.getMessage());
            return null;
        }
    }

    public MimeMessage decodeMimeBodyParts(MimeBodyPart mimeBodyPart) {
        return decodeMimeBodyParts(mimeBodyPart, true);
    }

    public MimeMessage decodeMimeBodyParts(MimeBodyPart mimeBodyPart, Boolean decodeBase64Parts) {
        try {
            Log.d(SMileCrypto.LOG_TAG, "Try to decode MimeBodyPart…");
            if(decodeBase64Parts)
                Log.d(SMileCrypto.LOG_TAG, "Will decode base64-text-parts if such parts exist.");
            else
                Log.d(SMileCrypto.LOG_TAG, "Will not decode base64-text-parts.");

            Properties props = System.getProperties();
            Session session = Session.getDefaultInstance(props, null);
            MimeMessage newMimeMessage = new MimeMessage(session);

            Multipart multipart = new MimeMultipart("alternative");

            String decryptedPart = convertMimeBodyPartToString(mimeBodyPart);
            String[] lines = decryptedPart.split("\n");
            Boolean possibleConvert = false;
            Boolean convert = false;
            InternetHeaders headers = null;
            String newContent = null;
            Boolean isAttachment = false;
            Boolean headerPossible = true;
            //int i = -1;
            for (String line : lines) {
                line = line.replace("\r", "");
                //i++;
                //Log.d(SMileCrypto.LOG_TAG, i + ". LINE is: " + line + "\n\n");
                if (line.startsWith("------") || line.startsWith("--Apple-Mail=")) {
                    //Log.d(SMileCrypto.LOG_TAG, i + ". First case!" + "\n\n");
                    if (headers != null && newContent != null) {
                        MimeBodyPart bodyPart;
                        //Log.d(SMileCrypto.LOG_TAG, i + ". headers: " + headers.getAllHeaderLines().nextElement());
                        if (convert && decodeBase64Parts) {
                            //Log.d(SMileCrypto.LOG_TAG, i + ". Convert: " + newContent);
                            byte[] decoded = Base64.decode(newContent, 0);
                            //Log.d(SMileCrypto.LOG_TAG, i + ". DECODED: " + new String(decoded));

                            bodyPart = new MimeBodyPart(headers, decoded);
                        } else {
                            //Log.d(SMileCrypto.LOG_TAG, i + ". not convert: " + newContent);
                            if (newContent.equals(""))
                                newContent = "\n";
                            bodyPart = new MimeBodyPart(headers, newContent.getBytes());
                        }
                        //Log.d(SMileCrypto.LOG_TAG, i + ". clear all" + "\n\n");
                        multipart.addBodyPart(bodyPart);
                        headers = null;
                        convert = false;
                        possibleConvert = false;
                        newContent = null;
                        isAttachment = false;
                        headerPossible = true;
                    } else {
                        //Log.d(SMileCrypto.LOG_TAG, i + ". header null -- continue!" + "\n\n");
                        headerPossible = true;
                    }
                } else if (line.contains("boundary=\"---")) {
                    continue;
                } else if (headerPossible && line.startsWith("Content-Type")) {
                    if ((line.contains("text/plain") || line.contains("text/html")) && line.contains("utf-8")) {
                        possibleConvert = true;
                    } else if (line.contains("multipart/alternative"))
                        continue; //ignore

                    headers = new InternetHeaders();
                    //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + line + "\n\n");
                    headers.addHeaderLine(line);
                } else if (headerPossible && line.startsWith("Content-Transfer-Encoding")) {
                    if (line.contains("base64")) {
                        if (possibleConvert && decodeBase64Parts) {
                            convert = true;
                            headers.addHeaderLine("Content-Transfer-Encoding: quoted-printable");
                            //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + "Content-Transfer-Encoding: quoted-printable" + "\n\n");
                            continue;
                        }
                    }
                    //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + line + "\n\n");
                    if (headers == null)
                        headers = new InternetHeaders();
                    headers.addHeaderLine(line);
                } else if (headerPossible && line.startsWith("Content-Disposition")) {
                    //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + line + "\n\n");
                    if (headers == null)
                        headers = new InternetHeaders();
                    headers.addHeaderLine(line);
                    if(line.contains("attachment"))
                        isAttachment = true;
                } else if (headerPossible && line.contains("charset")) {
                    //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + line + "\n\n");
                    if (headers == null)
                        headers = new InternetHeaders();
                    headers.addHeaderLine(line);
                } else if (headerPossible && isAttachment && (line.contains("filename=") || line.contains("name="))){
                    //Log.d(SMileCrypto.LOG_TAG, i + ". add header line: " + line + "\n\n");
                    if (headers == null)
                        headers = new InternetHeaders();
                    headers.addHeaderLine(line);
                } else if (line.equals("\n") || line.equals("\r")) {
                    //Log.d(SMileCrypto.LOG_TAG, i + ". empty line" + "\n\n");
                    continue;
                } else {
                    headerPossible = false;
                    line = line.replaceAll("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]", "");
                    //Log.d(SMileCrypto.LOG_TAG, i + ". old content " + newContent + "\n\n");
                    if (newContent == null)
                        newContent = "";
                    newContent += line + "\n";
                    //Log.d(SMileCrypto.LOG_TAG, i + ". new content " + newContent + "\n\n");
                }
            }
            newMimeMessage.setContent(multipart);
            newMimeMessage.saveChanges();
            /*
            MimeMultipart mpart = (MimeMultipart) newMimeMessage.getContent();
            int cnt = mpart.getCount();
            for (int i = 0; i < cnt; i++) {
                BodyPart b = mpart.getBodyPart(i);
                BufferedInputStream bis = new BufferedInputStream((QPDecoderStream) b.getContent());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    while (true) {
                        int c = bis.read();
                        if (c == -1) {
                            break;
                        }
                        baos.write(c);
                    }

                Log.e(SMileCrypto.LOG_TAG, "Decrypt Content: " + new String(baos.toByteArray()));
                Log.e(SMileCrypto.LOG_TAG, "Decrypt toString: " + b.toString());
                Log.e(SMileCrypto.LOG_TAG, "Decrypt getDescription: " + b.getDescription());

            }*/
            Log.d(SMileCrypto.LOG_TAG, "… finished decoding MimeBodyPart.");
            return newMimeMessage;
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Exception decoding parts: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
            return null;
        }
    }

    public String convertMimeBodyPartToString(MimeBodyPart mimeBodyPart) {
        try {
            if (mimeBodyPart == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mimeBodyPart.getDataHandler().writeTo(baos);

            return baos.toString();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error converting MimeBodyPart to String: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
            return null;
        }
    }

    public String convertMimeMessageToString(MimeMessage mimeMessage) {
        try {
            if (mimeMessage == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
                return null;
            }

            String result = "";
            Enumeration allHeaders = mimeMessage.getAllHeaders();
            while (allHeaders.hasMoreElements()) {
                Header header = (Header) allHeaders.nextElement();
                result += header.getName() + ": " + header.getValue() + "\n";
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ((Multipart) mimeMessage.getContent()).writeTo(bytes);
            return result + bytes.toString();
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error converting MimeMessage to String: " + e.getMessage());
            e.printStackTrace();
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
            return null;
        }
    }

    public String getTextPlainFromMimeMessage(MimeMessage mimeMessage) {
        try {
            if (mimeMessage == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
                return null;
            }

            MimeMultipart mimeMultipart = (MimeMultipart) mimeMessage.getContent();
            int cnt = mimeMultipart.getCount();
            for (int i = 0; i < cnt; i++) {
                BodyPart b = mimeMultipart.getBodyPart(i);
                if (b.isMimeType("text/plain")) {
                    if (b.getContent() instanceof String) {
                        return (String) b.getContent();
                    } else if (b.getContent() instanceof QPDecoderStream) {
                        BufferedInputStream bis = new BufferedInputStream((QPDecoderStream) b.getContent());
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        while (true) {
                            int c = bis.read();
                            if (c == -1) {
                                break;
                            }
                            baos.write(c);
                        }
                        return new String(baos.toByteArray());
                    } else {
                        Log.d(SMileCrypto.LOG_TAG, "b.getContent was instance of: " + b.getContentType());
                        SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error extracting text/plain from MimeMessage: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
        }
        return null;
    }

    public String getTextHtmlFromMimeMessage(MimeMessage mimeMessage) {
        try {
            if (mimeMessage == null) {
                SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_INVALID_PARAMETER;
                return null;
            }

            MimeMultipart mimeMultipart = (MimeMultipart) mimeMessage.getContent();
            int cnt = mimeMultipart.getCount();
            for (int i = 0; i < cnt; i++) {
                BodyPart b = mimeMultipart.getBodyPart(i);
                if (b.isMimeType("text/html")) {
                    if (b.getContent() instanceof String) {
                        return (String) b.getContent();
                    } else if (b.getContent() instanceof QPDecoderStream) {
                        BufferedInputStream bis = new BufferedInputStream((QPDecoderStream) b.getContent());
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        while (true) {
                            int c = bis.read();
                            if (c == -1) {
                                break;
                            }
                            baos.write(c);
                        }
                        return new String(baos.toByteArray());
                    } else {
                        Log.d(SMileCrypto.LOG_TAG, "b.getContent was instance of: " + b.getContentType());
                        SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error extracting text/plain from MimeMessage: " + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
        }
        return null;
    }

    public MimeMessage addOldHeaders(MimeMessage newMimeMessage) {
        if (encryptedMimeMessage == null) {
            return newMimeMessage;
        }

        try {
            int i = 0;
            Log.d(SMileCrypto.LOG_TAG, "Add headers from encrypted MimeMessage.");
            Enumeration allHeaders = encryptedMimeMessage.getAllHeaders();
            while (allHeaders.hasMoreElements()) {
                Header header = (Header) allHeaders.nextElement();
                //Log.d(SMileCrypto.LOG_TAG, "Headername + value: " + header.getName() + " "+ header.getValue());
                newMimeMessage.addHeader(header.getName(), header.getValue());
                i++;
            }
            Log.d(SMileCrypto.LOG_TAG, "Added " + i + " headers from encrypted MimeMessage.");
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Exception in addOldHeaders:" + e.getMessage());
            SMileCrypto.EXIT_STATUS = SMileCrypto.STATUS_UNKNOWN_ERROR;
        }
        return newMimeMessage;
    }

    public Boolean hasPassphrase(String alias) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(App.getContext().
                getApplicationContext());
        return preferences.contains(alias + "-passphrase");
    }

    private class AsyncDecryptMail extends AsyncTask<Void, Void, MimeBodyPart> {

        protected MimeBodyPart doInBackground(Void... params) {
            if (alias == null || encryptedMimeMessage == null) {
                return decryptMailSynchronous(pathToFile, passphrase);
            }

            return decryptMailSynchronous(alias, encryptedMimeMessage, passphrase);
        }
    }

    private class AsyncDecryptEncodeMail extends AsyncTask<Void, Void, MimeMessage> {

        protected MimeMessage doInBackground(Void... params) {
            MimeBodyPart mimeBodyPart;
            if (alias == null || encryptedMimeMessage == null) {
                mimeBodyPart = decryptMailSynchronous(pathToFile, passphrase);
            } else {
                mimeBodyPart = decryptMailSynchronous(alias, encryptedMimeMessage, passphrase);
            }

            return addOldHeaders(decodeMimeBodyParts(mimeBodyPart));
        }
    }
}
