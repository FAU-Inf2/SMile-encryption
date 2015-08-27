package de.fau.cs.mad.smile.android.encryption;


import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;
import android.widget.Toast;

import org.spongycastle.mail.smime.SMIMEException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import korex.mail.Address;
import korex.mail.MessagingException;
import korex.mail.Session;
import korex.mail.internet.InternetAddress;
import korex.mail.internet.MimeBodyPart;
import korex.mail.internet.MimeMessage;

public class EncryptLocalMailActivity extends ActionBarActivity {

    private Toolbar toolbar;
    private TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decrypt_local_mail);

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.navigation_decrypt_local_mail);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mTextView = (TextView) findViewById(R.id.decrypt_local_mail_text_view);
        mTextView.setTextSize(12);
        mTextView.setMovementMethod(new ScrollingMovementMethod());
        doStuff();
    }

    private void doStuff() {
        new EncryptDecryptTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class EncryptDecryptTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            try {
                final EncryptMail encryptMail = new EncryptMail();
                final DecryptMail decryptMail = new DecryptMail();
                File downloadDir = new File("/sdcard/Download/");
                File sourceFile = new File(downloadDir, "Test-unencrypted.eml");

                Properties props = System.getProperties();
                Session session = Session.getDefaultInstance(props, null);
                MimeMessage mimeMessage = new MimeMessage(session, new FileInputStream(sourceFile));
                Address recipient = mimeMessage.getAllRecipients()[0];
                MimeMessage encryptMessage = encryptMail.encryptMessage(mimeMessage, recipient);

                if (encryptMessage != null) {
                    File encryptedFile = new File(downloadDir, "encrypted.eml");
                    encryptMessage.writeTo(new FileOutputStream(encryptedFile));

                    // decrypt again
                    mimeMessage = new MimeMessage(session, new FileInputStream(encryptedFile));
                    MimeBodyPart encryptedPart = new MimeBodyPart();
                    encryptedPart.setContent(mimeMessage.getContent(), mimeMessage.getContentType());
                    MimeBodyPart decryptedPart = decryptMail.decryptMail(encryptedPart, recipient.toString());
                    //mimeMessage.setContent(decryptedPart.getContent(), decryptedPart.getContentType());
                    //mimeMessage.saveChanges();
                    File decryptedFile = new File(downloadDir, "decrypted.eml");
                    decryptedPart.writeTo(new FileOutputStream(decryptedFile));
                }
            } catch (CertificateException e) {
                e.printStackTrace();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (KeyStoreException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (MessagingException e) {
                e.printStackTrace();
            } catch (SMIMEException e) {
                e.printStackTrace();
            }

            return null;
        }
    }
}
