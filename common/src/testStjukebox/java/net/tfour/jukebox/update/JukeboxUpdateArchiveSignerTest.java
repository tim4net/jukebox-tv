package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build 2475, Nina 2475 gazebo failure (Fire OS 7, API 28; logs
 * roux2475.*): "verify failed: wrong signer: no signing cert in the
 * archive" although the 2476 apk is a valid v1+v2 signed apk.
 *
 * These tests exercise the REAL {@link JukeboxUpdatePresenter#archiveSigningCertSha256(String)}
 * path (PackageManager {@code getPackageArchiveInfo} with the archive
 * signing flags) against a REAL signed apk fixture — no seam stubs, no
 * faked digests:
 *
 * <ul>
 *   <li>fixture {@code fixtures/jukebox-signed-fixture.apk}: real apk,
 *       package net.tfour.jukebox, versionCode 2476, v1+v2 signed by a
 *       throwaway test key whose cert SHA-256 is
 *       {@code 0B04F97F473A79EF587343770FE98BB6DAE4B68D2563F8E5D7E4DF512660CADB};</li>
 *   <li>on API 28 the archive parsing returns no signingInfo/signatures
 *       for an archive, so the production path extracts NO cert and the
 *       verify fails with "no signing cert in the archive".</li>
 * </ul>
 *
 * Phase 1 (failing tests only): the fixture tests below FAIL against the
 * current production code, reproducing Nina 2475 exactly (the extracted
 * digest is null instead of the fixture cert's). The tampered / unsigned
 * guards must keep refusing forever.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateArchiveSignerTest {

    /** The fixture signer cert's SHA-256 (uppercase hex, no spaces). */
    private static final String FIXTURE_CERT_SHA256 =
            "0B04F97F473A79EF587343770FE98BB6DAE4B68D2563F8E5D7E4DF512660CADB";

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
    }

    /** A presenter with NO seam overrides: the real archive signer path. */
    private JukeboxUpdatePresenter realPresenter() {
        return new JukeboxUpdatePresenter(app, () -> (Activity) null);
    }

    /** Copies the real signed fixture apk to a fresh temp file and returns it. */
    private File fixtureApk() throws IOException {
        return copyResourceToTemp("/fixtures/jukebox-signed-fixture.apk");
    }

    private File copyResourceToTemp(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull("fixture must be on the test classpath: " + resource, in);
            File dest = File.createTempFile("jukebox-fixture", ".apk",
                    app.getCacheDir());
            dest.deleteOnExit();
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return dest;
        }
    }

    /**
     * The fixture as Nina shipped it in 2476: a valid v1+v2 signed apk.
     * The real production path must extract the signer cert's SHA-256 —
     * and it must be the fixture key's cert digest, never null.
     */
    @Test
    public void realArchivePathExtractsTheFixtureSignerCertAtSdk28() throws IOException {
        String certSha256 = realPresenter().archiveSigningCertSha256(fixtureApk().getPath());

        // Nina 2475: at sdk 28 PackageManager archive parsing returns no
        // signingInfo/signatures for the archive, so this is null and the
        // verify logs "no signing cert in the archive" for a VALID apk.
        assertNotNull("Nina 2475: a valid v1+v2 signed archive must yield a "
                + "signing cert (got none at sdk 28)", certSha256);
        assertEquals("the archive signer cert digest must be the fixture key's",
                FIXTURE_CERT_SHA256, certSha256.trim().toUpperCase());
    }

    /** The same reproduction on another sdk (the fix must not be 28-only). */
    @Test
    @Config(sdk = 33)
    public void realArchivePathExtractsTheFixtureSignerCertAtSdk33() throws IOException {
        String certSha256 = realPresenter().archiveSigningCertSha256(fixtureApk().getPath());

        assertNotNull("a valid v1+v2 signed archive must yield a signing "
                + "cert (got none at sdk 33)", certSha256);
        assertEquals("the archive signer cert digest must be the fixture key's",
                FIXTURE_CERT_SHA256, certSha256.trim().toUpperCase());
    }

    /**
     * A tampered copy (one signed entry's bytes modified) must NEVER yield
     * the fixture cert: the digest break must be detected and the apk
     * refused as unsigned (no trusted cert extracted).
     */
    @Test
    public void tamperedSignedEntryYieldsNoTrustedCert() throws IOException {
        File tampered = tamperSignedEntry(fixtureApk());
        String certSha256 = realPresenter().archiveSigningCertSha256(tampered.getPath());

        assertNull("a tampered archive must be refused (no cert extracted), "
                        + "never accepted as the fixture signer: " + certSha256,
                certSha256);
    }

    /** An unsigned apk (no META-INF signatures) must be refused: no cert. */
    @Test
    public void unsignedApkYieldsNoSigningCert() throws IOException {
        File unsigned = stripMetaInfSignatures(fixtureApk());
        String certSha256 = realPresenter().archiveSigningCertSha256(unsigned.getPath());

        assertNull("an unsigned archive must yield no signing cert", certSha256);
    }

    // ---- Issue #9, items 4/5 (phase 1: failing tests only) ----

    /**
     * Issue #9, item 4: when PackageManager ALSO returns a cert for the
     * archive (the v2 block), it must equal the v1 (JarFile) cert — a
     * GENUINE v1 signature plus a FOREIGN v2 cert must NOT pass. The v1
     * cert here is the real fixture key's; PackageManager is made to
     * report a foreign cert for the same archive.
     */
    @Test
    public void foreignPackageManagerArchiveCertIsRefusedDespiteAGenuineV1Signature() throws IOException {
        File apk = fixtureApk();
        PackageInfo foreign = new PackageInfo();
        foreign.packageName = "net.tfour.jukebox";
        foreign.signatures = new Signature[]{new Signature(new byte[]{7, 7, 7, 7})};
        ((ShadowPackageManager) Shadows.shadowOf(app.getPackageManager()))
                .setPackageArchiveInfo(apk.getPath(), foreign);

        String certSha256 = realPresenter().archiveSigningCertSha256(apk.getPath());

        assertNull("genuine v1 + foreign package-manager cert must be refused"
                + " (no cert extracted), got " + certSha256, certSha256);
    }

    /**
     * PR #11 (a) / issue #9 item 5, real copied cert: the fixture's signer
     * certificate stays inside META-INF/*.RSA, but the .SF is signed by a
     * freshly generated RSA key. The PKCS#7 is well-formed (it parses, the
     * embedded cert is the fixture cert, and the signature verifies under the
     * other key). Archive extraction must return null — the copied cert must
     * not vouch for a signature it did not make. A one-byte .SF flip is not
     * this test: that archive already fails closed on a broken signature file
     * without proving the cert and the signature key were compared.
     */
    @Test
    public void copiedCertWithAForgedSignatureYieldsNoTrustedCert() throws Exception {
        File apk = fixtureApk();
        // Same zip rewrite, original .RSA kept: a null here would mean the
        // copied-cert case failed because the archive no longer parses.
        String repacked = realPresenter().archiveSigningCertSha256(
                rewriteArchive(apk, null).getPath());
        assertEquals("rewriting the zip while keeping the real signature block "
                        + "must still yield the fixture cert",
                FIXTURE_CERT_SHA256, upper(repacked));

        CopiedCertArchive copied = copiedCertArchive(apk);
        assertFalse("the .SF signature must fail under the fixture cert's public key",
                copied.verifiesUnderFixtureCert);
        assertTrue("the .SF signature must verify under the other key "
                        + "(this is signature verification, not a broken encoding)",
                copied.verifiesUnderOtherKey);
        assertArrayEquals("META-INF/*.RSA must still embed the fixture certificate",
                copied.fixtureCertEncoded, copied.embeddedCertEncoded);

        String certSha256 = realPresenter().archiveSigningCertSha256(copied.apk.getPath());

        assertNull("a copied fixture cert signed by a different key must yield no cert",
                certSha256);
    }

    /**
     * PR #11 (b): {@code packageManagerArchiveSigningCertSha256} catches
     * {@code Throwable}. An Error that is neither a {@code RuntimeException}
     * nor a {@code LinkageError} — here, hashing the cert bytes PackageManager
     * reported for the archive — must propagate. Today the catch swallows it
     * and the jar v1 cert is returned.
     */
    @Test
    public void packageManagerErrorOutsideRuntimeExceptionAndLinkageErrorPropagates()
            throws IOException {
        File apk = fixtureApk();
        boolean[] threw = {false};
        ArchiveParseError error = new ArchiveParseError();
        installArchiveSignatureThatThrows(apk, error, threw);

        String certSha256 = null;
        ArchiveParseError propagated = null;
        try {
            certSha256 = realPresenter().archiveSigningCertSha256(apk.getPath());
        } catch (ArchiveParseError thrown) {
            propagated = thrown;
        }

        assertTrue("PackageManager's archive signature bytes must be read, so the "
                        + "Error is thrown inside the archive-parse catch",
                threw[0]);
        if (propagated == null) {
            fail("catch (Throwable) swallowed " + error.getClass().getSimpleName()
                    + " from package-manager archive parsing and returned " + certSha256
                    + ". The catch must be RuntimeException | LinkageError so this Error "
                    + "propagates.");
        }
        assertTrue("the propagated Error must be the one the archive cert threw",
                propagated == error);
    }

    /**
     * Guard for the same catch: a RuntimeException from package-manager archive
     * parsing stays tolerated, and the jar v1 cert is still returned.
     */
    @Test
    public void packageManagerRuntimeExceptionIsStillSwallowed() throws IOException {
        File apk = fixtureApk();
        boolean[] threw = {false};
        installArchiveSignatureThatThrows(apk, new IllegalStateException("archive parse"), threw);

        String certSha256 = realPresenter().archiveSigningCertSha256(apk.getPath());

        assertTrue("PackageManager's archive signature bytes must be read", threw[0]);
        assertEquals("a RuntimeException from package-manager archive parsing must be "
                        + "swallowed and the jar v1 cert kept",
                FIXTURE_CERT_SHA256, upper(certSha256));
    }

    /**
     * Guard for the same catch: a LinkageError from package-manager archive
     * parsing stays tolerated, and the jar v1 cert is still returned.
     */
    @Test
    public void packageManagerLinkageErrorIsStillSwallowed() throws IOException {
        File apk = fixtureApk();
        boolean[] threw = {false};
        installArchiveSignatureThatThrows(apk,
                new NoClassDefFoundError("archive verifier"), threw);

        String certSha256 = realPresenter().archiveSigningCertSha256(apk.getPath());

        assertTrue("PackageManager's archive signature bytes must be read", threw[0]);
        assertEquals("a LinkageError from package-manager archive parsing must be "
                        + "swallowed and the jar v1 cert kept",
                FIXTURE_CERT_SHA256, upper(certSha256));
    }

    /**
     * Error that is not a LinkageError. Package-manager archive parsing must
     * not swallow this once the catch narrows to RuntimeException | LinkageError.
     */
    public static final class ArchiveParseError extends Error {
        public ArchiveParseError() {
            super("package-manager archive parse");
        }
    }

    // ---- helpers: real-zip mutations of the real fixture ----

    /** A rewritten archive whose .RSA carries the fixture cert and a foreign signature. */
    private static final class CopiedCertArchive {
        final File apk;
        final byte[] fixtureCertEncoded;
        final byte[] embeddedCertEncoded;
        final boolean verifiesUnderFixtureCert;
        final boolean verifiesUnderOtherKey;

        CopiedCertArchive(File apk, byte[] fixtureCertEncoded, byte[] embeddedCertEncoded,
                boolean verifiesUnderFixtureCert, boolean verifiesUnderOtherKey) {
            this.apk = apk;
            this.fixtureCertEncoded = fixtureCertEncoded;
            this.embeddedCertEncoded = embeddedCertEncoded;
            this.verifiesUnderFixtureCert = verifiesUnderFixtureCert;
            this.verifiesUnderOtherKey = verifiesUnderOtherKey;
        }
    }

    /**
     * Keeps the fixture .SF bytes and the fixture certificate, and rebuilds
     * META-INF/*.RSA as a detached SHA-256/RSA PKCS#7 whose signature was
     * produced by a new throwaway key.
     */
    private static CopiedCertArchive copiedCertArchive(File apk) throws Exception {
        byte[] sfBytes;
        byte[] rsaBytes;
        try (ZipFile zip = new ZipFile(apk)) {
            sfBytes = readSuffixed(zip, ".SF");
            rsaBytes = readSuffixed(zip, ".RSA");
        }
        X509Certificate fixtureCert = certificateFromSignatureBlock(rsaBytes);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        byte[] foreignSignature = signSha256Rsa(sfBytes, other.getPrivate());
        byte[] forgedBlock = pkcs7DetachedSha256Rsa(fixtureCert, foreignSignature);
        X509Certificate embedded = certificateFromSignatureBlock(forgedBlock);
        return new CopiedCertArchive(
                rewriteArchive(apk, forgedBlock),
                fixtureCert.getEncoded(),
                embedded.getEncoded(),
                verifiesSha256Rsa(sfBytes, foreignSignature, fixtureCert.getPublicKey()),
                verifiesSha256Rsa(sfBytes, foreignSignature, other.getPublic()));
    }

    private static byte[] signSha256Rsa(byte[] data, java.security.PrivateKey key) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    private static boolean verifiesSha256Rsa(byte[] data, byte[] signatureBytes,
            java.security.PublicKey key) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(data);
        try {
            return signature.verify(signatureBytes);
        } catch (SignatureException rejected) {
            return false;
        }
    }

    /** Detached PKCS#7 SignedData: fixture cert, signature bytes from the caller. */
    private static byte[] pkcs7DetachedSha256Rsa(X509Certificate cert, byte[] signature)
            throws Exception {
        AlgorithmIdentifier sha256 = new AlgorithmIdentifier(
                NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);
        AlgorithmIdentifier rsa = new AlgorithmIdentifier(
                PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);
        X500Name issuer = X500Name.getInstance(cert.getIssuerX500Principal().getEncoded());
        SignerInfo signerInfo = new SignerInfo(
                new ASN1Integer(1),
                new IssuerAndSerialNumber(issuer, cert.getSerialNumber()),
                sha256,
                null,
                rsa,
                new DEROctetString(signature),
                null);
        ASN1Encodable certAsn1 = ASN1Primitive.fromByteArray(cert.getEncoded());
        SignedData signedData = new SignedData(
                new ASN1Integer(1),
                new DERSet(sha256),
                new ContentInfo(PKCSObjectIdentifiers.data, null),
                new DERSet(certAsn1),
                null,
                new DERSet(signerInfo));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, signedData).getEncoded("DER");
    }

    private static X509Certificate certificateFromSignatureBlock(byte[] block) throws Exception {
        ContentInfo outer = ContentInfo.getInstance(ASN1Primitive.fromByteArray(block));
        SignedData signedData = SignedData.getInstance(outer.getContent());
        byte[] encoded = signedData.getCertificates().getObjectAt(0)
                .toASN1Primitive().getEncoded("DER");
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
    }

    /**
     * Points PackageManager's archive info at {@code apk} and makes reading
     * that reported signature throw {@code error} from inside the production
     * archive-parse catch.
     */
    private void installArchiveSignatureThatThrows(File apk, Throwable error, boolean[] threw) {
        PackageInfo info = new PackageInfo();
        info.packageName = "net.tfour.jukebox";
        info.signatures = new Signature[]{new Signature(new byte[]{1}) {
            @Override
            public byte[] toByteArray() {
                threw[0] = true;
                if (error instanceof RuntimeException) {
                    throw (RuntimeException) error;
                }
                if (error instanceof Error) {
                    throw (Error) error;
                }
                throw new IllegalStateException(error);
            }
        }};
        ((ShadowPackageManager) Shadows.shadowOf(app.getPackageManager()))
                .setPackageArchiveInfo(apk.getPath(), info);
    }

    private static String upper(String hex) {
        return hex == null ? null : hex.trim().toUpperCase();
    }

    /**
     * Rewrites the apk. When {@code replacementRsa} is non-null, META-INF/*.RSA
     * is replaced and every other entry's bytes are copied. A null replacement
     * copies every entry unchanged (the v2 block, which is not a zip entry, is
     * dropped either way).
     */
    private static File rewriteArchive(File apk, byte[] replacementRsa) throws IOException {
        File out = File.createTempFile("jukebox-resigned", ".apk", apk.getParentFile());
        out.deleteOnExit();
        boolean replaced = false;
        try (ZipFile zip = new ZipFile(apk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] content = readFully(zip, entry);
                if (replacementRsa != null && entry.getName().startsWith("META-INF/")
                        && entry.getName().endsWith(".RSA")) {
                    content = replacementRsa;
                    replaced = true;
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                zos.write(content);
                zos.closeEntry();
            }
        }
        if (replacementRsa != null) {
            assertTrue("fixture must contain a META-INF/*.RSA entry", replaced);
        }
        return out;
    }

    private static byte[] readSuffixed(ZipFile zip, String suffix) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("META-INF/") && name.endsWith(suffix)) {
                return readFully(zip, entry);
            }
        }
        throw new IOException("fixture has no META-INF/*" + suffix);
    }

    /** Rewrites the zip with one byte of the signed AndroidManifest.xml flipped. */
    private static File tamperSignedEntry(File apk) throws IOException {
        File out = File.createTempFile("jukebox-tampered", ".apk",
                apk.getParentFile());
        out.deleteOnExit();
        try (ZipFile zip = new ZipFile(apk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            byte[] manifest = readEntry(zip, "AndroidManifest.xml");
            manifest[0] ^= (byte) 0xA5; // break the SHA-256 digest in MANIFEST.MF
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                zos.putNextEntry(new ZipEntry(entry.getName()));
                if ("AndroidManifest.xml".equals(entry.getName())) {
                    zos.write(manifest);
                } else {
                    zos.write(readFully(zip, entry));
                }
                zos.closeEntry();
            }
        }
        return out;
    }

    /** Rewrites the zip without the META-INF v1 signature files (.SF/.RSA/.DSA). */
    private static File stripMetaInfSignatures(File apk) throws IOException {
        File out = File.createTempFile("jukebox-unsigned", ".apk",
                apk.getParentFile());
        out.deleteOnExit();
        try (ZipFile zip = new ZipFile(apk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA")
                            || name.endsWith(".DSA"))) {
                    continue; // unsigned: no v1 signatures at all
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(readFully(zip, entry));
                zos.closeEntry();
            }
        }
        return out;
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull("entry must exist in the fixture: " + name, entry);
        return readFully(zip, entry);
    }

    private static byte[] readFully(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }
}