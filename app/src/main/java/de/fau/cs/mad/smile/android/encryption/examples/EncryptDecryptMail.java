package de.fau.cs.mad.smile.android.encryption.examples;

import android.os.AsyncTask;
import android.util.Log;

import java.util.Properties;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import de.fau.cs.mad.javax.activation.CommandMap;
import de.fau.cs.mad.javax.activation.MailcapCommandMap;
import de.fau.cs.mad.smile.android.encryption.SMileCrypto;
import de.fau.cs.mad.smile.android.encryption.SignMessage;
import de.fau.cs.mad.smile.android.encryption.DecryptMail;
import de.fau.cs.mad.smile.android.encryption.EncryptMail;

public class EncryptDecryptMail {
    final String content = "Content for MIME-Messages, üäÖß, México 42!";

    public void startTest() {
        System.out.println("Start EncryptDecryptMailTest.");
        try {
            Log.d(SMileCrypto.LOG_TAG, "create new MimeMessage…");
            MimeMessage originalMimeMessage = new AsyncCreateMimeMessage().execute().get();
            MimeMessage encryptedMimeMessage = encrypt(originalMimeMessage);
            encryptedMimeMessage.addFrom(new Address[]{new InternetAddress("fixmymail@t-online.de")});
            encryptedMimeMessage.addRecipients(Message.RecipientType.TO, "fixmymail@gmx.de");

            MimeBodyPart decrypted = decrypt(encryptedMimeMessage);
            //check
            Log.d(SMileCrypto.LOG_TAG, "check decrypted part…");
            MimeMultipart multipart = (MimeMultipart) decrypted.getContent();
            BodyPart part = multipart.getBodyPart(0);
            Log.d(SMileCrypto.LOG_TAG, "contentType is: " + part.getContentType());

            String decryptedResult = (String) part.getContent();
            if(decryptedResult.equals(content))
                Log.e(SMileCrypto.LOG_TAG, "SUCCESS! Decrypted Message is equals input message! :)");
            else {
                Log.e(SMileCrypto.LOG_TAG, "FAIL! Decrypted content is: " + decryptedResult);
                for(int i = 0; i < content.length(); i++) {
                    if(content.charAt(i) == decryptedResult.charAt(i))
                        Log.d(SMileCrypto.LOG_TAG, i + ". " + content.charAt(i) + "==" + decryptedResult.charAt(i));
                    else
                        Log.e(SMileCrypto.LOG_TAG, i + ". " + content.charAt(i) + "!=" + decryptedResult.charAt(i));
                }
            }


            //TODO: how to sign encrypted message?
            Log.e(SMileCrypto.LOG_TAG, "Start signing original message.");
            sign(originalMimeMessage);
            Log.e(SMileCrypto.LOG_TAG, "End signing.");
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private MimeMessage encrypt(MimeMessage originalMimeMessage)  throws Exception{
        System.out.println("Start encrypt.");

        EncryptMail encryptMail = new EncryptMail();
        return encryptMail.encryptMessage(originalMimeMessage);
    }

    private MimeMultipart sign(MimeMessage original) throws Exception{
        SignMessage signMessage = new SignMessage();

        MimeBodyPart mimeBodyPart;
        if(original.getContent() instanceof MimeMultipart) {
            MimeMultipart multipart = (MimeMultipart) original.getContent();
            mimeBodyPart = (MimeBodyPart) multipart.getBodyPart(0);
        } else {
            Log.e(SMileCrypto.LOG_TAG, "Type: " + original.getContent());
            Log.e(SMileCrypto.LOG_TAG, "User other example.");
            InternetHeaders headers = new InternetHeaders();
            headers.addHeader("Content-Type", "text/plain");
            headers.addHeader("Content-Transfer-Encoding", "quoted-printable");
            String message = "MESSAGE TO BE SIGNED!";
            mimeBodyPart = new MimeBodyPart(headers, message.getBytes());
        }

        MimeMultipart result = signMessage.sign(mimeBodyPart, new InternetAddress("fixmymail@gmx.de"));
        for(int i = 0; i < result.getCount(); i++) {
            Log.d(SMileCrypto.LOG_TAG, i + 1 + ". Part…");
            MimeBodyPart part = (MimeBodyPart) result.getBodyPart(i);
            Log.d(SMileCrypto.LOG_TAG, part.getContentType());
            Log.d(SMileCrypto.LOG_TAG, part.getContent().toString());
        }

        return result;
    }

    private MimeBodyPart decrypt(MimeMessage mimeMessage) throws Exception {
        System.out.println("Start decrypt.");

        DecryptMail decryptMail = new DecryptMail();
        return decryptMail.decryptMail((String) null, mimeMessage, null);
    }

    private class AsyncCreateMimeMessage extends AsyncTask<Void, Void, MimeMessage> {
        protected MimeMessage doInBackground(Void... params) {
            try {
                MailcapCommandMap mc = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
                mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
                mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml");
                mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
                mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
                mc.addMailcap("message/rfc822;; x-java-content- handler=com.sun.mail.handlers.message_rfc822");

                Properties props = System.getProperties();
                Session session = Session.getDefaultInstance(props, null);
                MimeMessage mimeMessage = new MimeMessage(session);

                MimeMultipart multipart = new MimeMultipart("alternative");
                InternetHeaders headers = new InternetHeaders();
                headers.addHeader("Content-Type", "text/plain; charset=utf-8");
                headers.addHeader("Content-Transfer-Encoding", "quoted-printable");
                MimeBodyPart bodyPart = new MimeBodyPart(headers, content.getBytes());
                multipart.addBodyPart(bodyPart);
                mimeMessage.setContent(multipart);
                mimeMessage.saveChanges();

                mimeMessage.addFrom(new Address[]{new InternetAddress("fixmymail@t-online.de")});
                mimeMessage.addRecipients(Message.RecipientType.TO, "fixmymail@gmx.de");
                mimeMessage.saveChanges();
                return mimeMessage;

            } catch (Exception e) {
                Log.e(SMileCrypto.LOG_TAG, "Error: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }

}