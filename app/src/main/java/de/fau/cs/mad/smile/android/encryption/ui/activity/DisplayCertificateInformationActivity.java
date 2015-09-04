package de.fau.cs.mad.smile.android.encryption.ui.activity;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ExpandableListView;
import android.widget.Toast;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.x500.RDN;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x500.style.BCStyle;
import org.spongycastle.asn1.x500.style.IETFUtils;
import org.spongycastle.cert.jcajce.JcaX509CertificateHolder;

import java.math.BigInteger;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import de.fau.cs.mad.smile.android.encryption.App;
import de.fau.cs.mad.smile.android.encryption.KeyInfo;
import de.fau.cs.mad.smile.android.encryption.R;
import de.fau.cs.mad.smile.android.encryption.SMileCrypto;
import de.fau.cs.mad.smile.android.encryption.crypto.KeyManagement;
import de.fau.cs.mad.smile.android.encryption.ui.AbstractCertificateInfoItem;
import de.fau.cs.mad.smile.android.encryption.ui.CertificateInformationItem;
import de.fau.cs.mad.smile.android.encryption.ui.CryptographicInformationItem;
import de.fau.cs.mad.smile.android.encryption.ui.PersonalInformationItem;
import de.fau.cs.mad.smile.android.encryption.ui.ValidityItem;
import de.fau.cs.mad.smile.android.encryption.ui.adapter.ExpandableCertificateListAdapter;
import de.fau.cs.mad.smile.android.encryption.utilities.Utils;

public class DisplayCertificateInformationActivity extends ActionBarActivity {
    private Toolbar toolbar;
    private String name;
    private String alias;
    private KeyInfo keyInfo;
    private HashMap<String, List<AbstractCertificateInfoItem>> listDataChild;
    private List<String> listDataHeader;
    ExpandableCertificateListAdapter listAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(SMileCrypto.LOG_TAG, "Started DisplayCertificateInformationActivity.");
        Bundle extras = getIntent().getExtras();
        this.alias = extras.getString("Alias");
        if (this.alias == null) {
            Log.e(SMileCrypto.LOG_TAG, "Called without alias.");
            finish();
        }
        Log.d(SMileCrypto.LOG_TAG, "Called with alias: " + alias);
        this.name = extras.getString("Name");

        setContentView(R.layout.activity_display_certificate_information);
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(this.name); //if (name == null) --> set later
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        ExpandableListView expListView = (ExpandableListView) findViewById(R.id.lvExp);

        // preparing list data
        getKeyInfo();

        //TODO: workaround to prevent crash
        if (listDataHeader == null || listDataChild == null) {
            Log.e(SMileCrypto.LOG_TAG, "ListDataHeader/ListDataChild was null.");
            finish();
        } else {
            listAdapter = new ExpandableCertificateListAdapter(this, listDataHeader, listDataChild);

            // setting list adapter
            expListView.setAdapter(listAdapter);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_display_certificate_information, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_delete) {
            deleteKey(this.keyInfo);
        } else if (id == R.id.action_export) {
            exportCertificate();
        }

        return super.onOptionsItemSelected(item);
    }

    private void getKeyInfo() {
        try {
            KeyManagement keyManagement = KeyManagement.getInstance();
            KeyInfo keyInfo = keyManagement.getKeyInfo(this.alias);
            this.keyInfo = keyInfo;
            extractCertificateInformation(keyInfo);
        } catch (Exception e) {
            Log.e(SMileCrypto.LOG_TAG, "Error: " + e.getMessage());
            showErrorPrompt();
        }
    }

    private void extractCertificateInformation(KeyInfo keyInfo) {
        if (this.name == null) {
            this.name = keyInfo.getContact();
            Log.d(SMileCrypto.LOG_TAG, "Name was null, set name to: " + this.name);
            toolbar.setTitle(this.name);
            setSupportActionBar(toolbar);
        }
        generatePersonalInformation(keyInfo);
    }

    private void generatePersonalInformation(KeyInfo keyInfo) {
        Log.d(SMileCrypto.LOG_TAG, "Setting personal information");
        listDataHeader = new ArrayList<>();
        listDataHeader.add(getString(R.string.personal));
        listDataChild = new HashMap<>();
        LinkedHashMap<String, String[]> data = new LinkedHashMap<>();
        X509Certificate certificate = keyInfo.getCertificate();
        if (certificate == null) {
            Log.e(SMileCrypto.LOG_TAG, "Certificate was null -- abort.");
            return;
        }
        X500Name x500name = null;
        try {
            x500name = new JcaX509CertificateHolder(certificate).getSubject();
            parseX500Name(data, x500name);
            ArrayList<AbstractCertificateInfoItem> pers = new ArrayList<>();
            PersonalInformationItem persI = new PersonalInformationItem();
            persI.buildComplex(data);
            pers.add(persI);
            listDataChild.put(listDataHeader.get(0), pers);
        } catch (CertificateEncodingException e) {
            Log.d(SMileCrypto.LOG_TAG, "Error with certificate encoding: " + e.getMessage());
            Toast.makeText(App.getContext(), getString(R.string.failed_extract), Toast.LENGTH_SHORT).show();
        }
        listDataHeader.add(getString(R.string.CA));
        LinkedHashMap<String, String[]> cadata = new LinkedHashMap<>();

        try {
            x500name = new JcaX509CertificateHolder(certificate).getIssuer();
            parseX500Name(cadata, x500name);
            ArrayList<AbstractCertificateInfoItem> pers = new ArrayList<>();
            PersonalInformationItem persI = new PersonalInformationItem();
            persI.buildComplex(cadata);
            pers.add(persI);
            listDataChild.put(listDataHeader.get(1), pers);
        } catch (CertificateEncodingException e) {
            Log.d(SMileCrypto.LOG_TAG, "Error with certificate encoding: " + e.getMessage());
            Toast.makeText(App.getContext(), getString(R.string.failed_extract), Toast.LENGTH_SHORT).show();
        }

        Log.d(SMileCrypto.LOG_TAG, "Setting validity information");
        DateTimeFormatter fmt = DateTimeFormat.forPattern("d MMMM yyyy - H:m:s");
        listDataHeader.add(getString(R.string.validity));
        HashMap<String, String> validity = new HashMap<>();
        validity.put("Startdate", keyInfo.getValidAfter().toString(fmt));
        validity.put("Enddate", keyInfo.getTerminationDate().toString(fmt));
        ArrayList<AbstractCertificateInfoItem> val = new ArrayList<>();
        ValidityItem validityItem = new ValidityItem();
        validityItem.build(validity);
        val.add(validityItem);
        listDataChild.put(listDataHeader.get(2), val);


        Log.d(SMileCrypto.LOG_TAG, "Setting certificate information");
        listDataHeader.add(getString(R.string.certificate));
        HashMap<String, String> certificateInfo = new HashMap<>();
        certificateInfo.put("Thumbprint", keyInfo.getThumbprint());
        BigInteger serialNumber = keyInfo.getCertificate().getSerialNumber();
        certificateInfo.put("Serial number", serialNumber.toString(16));
        certificateInfo.put("Version", Integer.toString(keyInfo.getCertificate().getVersion()));
        ArrayList<AbstractCertificateInfoItem> cert = new ArrayList<>();
        CertificateInformationItem certificateInformationItem = new CertificateInformationItem();
        certificateInformationItem.build(certificateInfo);
        cert.add(certificateInformationItem);
        listDataChild.put(listDataHeader.get(3), cert);

        Log.d(SMileCrypto.LOG_TAG, "Setting cryptographic information");
        listDataHeader.add(getString(R.string.cryptographic));
        HashMap<String, String> cryptographicInfo = new HashMap<>();
        PublicKey publicKey = keyInfo.getCertificate().getPublicKey();
        if (publicKey instanceof RSAPublicKey) {
            RSAPublicKey rsaPublicKey = (RSAPublicKey) publicKey;
            String modulus = rsaPublicKey.getModulus().toString(16);
            String exponent = rsaPublicKey.getPublicExponent().toString(16);
            cryptographicInfo.put("Public Key", "RSAPublicKey");
            cryptographicInfo.put("Modulus", modulus);
            cryptographicInfo.put("Exponent", exponent);
            cryptographicInfo.put("Signature Algorithm", keyInfo.getCertificate().getSigAlgName());
            cryptographicInfo.put("Signature", new BigInteger(keyInfo.getCertificate().getSignature()).toString(16));
        } else {
            Log.d(SMileCrypto.LOG_TAG, "Not an instance of RSAPublicKey.");
            cryptographicInfo.put("Public Key", keyInfo.getCertificate().getPublicKey().toString());
            cryptographicInfo.put("Signature Algorithm", keyInfo.getCertificate().getSigAlgName());
            cryptographicInfo.put("Signature", new BigInteger(keyInfo.getCertificate().getSignature()).toString(16));
        }
        ArrayList<AbstractCertificateInfoItem> crypto = new ArrayList<>();
        CryptographicInformationItem cryptographicInformationItem = new CryptographicInformationItem();
        cryptographicInformationItem.build(cryptographicInfo);
        crypto.add(cryptographicInformationItem);
        listDataChild.put(listDataHeader.get(4), crypto);

        /*
        * · Personal: Name, Email, SubjectDN
        · Validity: start date, end date
        · Certificate: Version, Serial Number, Thumbprint
        · Issuer: IssuerDN (splitted)
        · Cryptographic: Public Key, Signature algo, Signature */
    }

    private void parseX500Name(LinkedHashMap<String, String[]> data, X500Name x500name) {
        Resources res = getResources();
        String[] keys = res.getStringArray(R.array.info_keys);
        String[] entries = res.getStringArray(R.array.info_names);
        for (int i = 0; i < Utils.asn1ObjectIdentifiers.length && i < keys.length && i < entries.length; ++i) {
            RDN[] rdns = x500name.getRDNs(Utils.asn1ObjectIdentifiers[i]);
            if (rdns.length > 0) {
                String[] values = new String[2];
                values[0] = entries[i];
                values[1] = IETFUtils.valueToString(rdns[0].getFirst().getValue());
                data.put(keys[i], values);
            }
        }
    }

    private void deleteKey(final KeyInfo keyInfo) {
        final KeyManagement keyManagement;
        try {
            keyManagement = KeyManagement.getInstance();
        } catch (Exception e) {
            showErrorPrompt();
            return;
        }

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        if (keyInfo.getAlias().startsWith("SMile_crypto_own")) {
            alertDialogBuilder.setTitle(getString(R.string.alert_header_start) + keyInfo.getContact() + getString(R.string.alert_header_end));
            alertDialogBuilder
                    .setMessage(getString(R.string.alert_content))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.erase), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Boolean success = keyManagement.deleteKey(keyInfo.getAlias());
                            if (success)
                                Toast.makeText(App.getContext(),
                                        R.string.certificate_deleted, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });

            alertDialogBuilder.create().show();
        } else {
            alertDialogBuilder
                    .setMessage(getString(R.string.alert_header_start) + keyInfo.getContact() + getString(R.string.alert_header_end))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.erase), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Boolean success = keyManagement.deleteKey(keyInfo.getAlias());
                            if (success)
                                Toast.makeText(App.getContext(),
                                        R.string.certificate_deleted, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });
            alertDialogBuilder.create().show();
        }
    }

    private void exportCertificate() {
        Log.d(SMileCrypto.LOG_TAG, "Try to export certificate.");
        if (this.alias.contains("_own_")) {
            exportOwnCertificate();
        } else if (this.alias.contains("_other_")) {
            exportOtherCertificate();
        } else {
            //this should not happen
            Log.e(SMileCrypto.LOG_TAG, "Tried to export certificate with invalid alias: " + alias);
        }
    }

    private void exportOwnCertificate() {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.alert_header_export));
        alertDialogBuilder
                .setMessage(getString(R.string.alert_export))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.export), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        String dst = KeyManagement.copyP12ToSDCard(alias);
                        if (dst == null) {
                            Toast.makeText(App.getContext(),
                                    getString(R.string.certificate_export_fail), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(App.getContext(),
                                    getString(R.string.certificate_export_success) + dst, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });

        alertDialogBuilder.create().show();
    }

    private void exportOtherCertificate() {
        String dst = KeyManagement.copyCertificateToSDCard(keyInfo.getCertificate(), alias);
        if (dst == null) {
            Toast.makeText(App.getContext(),
                    getString(R.string.certificate_export_fail), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(App.getContext(),
                    getString(R.string.certificate_export_success) + dst, Toast.LENGTH_LONG).show();
        }
    }

    private void showErrorPrompt() {
        AlertDialog.Builder builder = new AlertDialog.Builder(DisplayCertificateInformationActivity.this);
        builder.setTitle(getResources().getString(R.string.error));
        Log.e(SMileCrypto.LOG_TAG, "EXIT_STATUS: " + SMileCrypto.EXIT_STATUS);
        builder.setMessage(getResources().getString(R.string.internal_error));
        builder.setPositiveButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        });
        builder.create().show();
    }
}