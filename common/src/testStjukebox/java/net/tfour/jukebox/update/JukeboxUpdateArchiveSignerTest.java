package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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

        assertEquals("a tampered archive must be refused (no cert extracted), "
                        + "never accepted as the fixture signer: " + certSha256,
                null, certSha256);
    }

    /** An unsigned apk (no META-INF signatures) must be refused: no cert. */
    @Test
    public void unsignedApkYieldsNoSigningCert() throws IOException {
        File unsigned = stripMetaInfSignatures(fixtureApk());
        String certSha256 = realPresenter().archiveSigningCertSha256(unsigned.getPath());

        assertEquals("an unsigned archive must yield no signing cert", null, certSha256);
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

        assertEquals("genuine v1 + foreign package-manager cert must be refused"
                + " (no cert extracted), got " + certSha256, null, certSha256);
    }

    /**
     * Issue #9, item 5 (copied cert): a copy of the fixture that KEEPS the
     * META-INF cert (the .RSA block) but mutates the .SF so its signature no
     * longer verifies must yield NO trusted cert — the copied cert alone must
     * never vouch for the archive.
     */
    @Test
    public void copiedCertWithAForgedSignatureYieldsNoTrustedCert() throws IOException {
        File forged = forgeMetaInfSf(fixtureApk());

        String certSha256 = realPresenter().archiveSigningCertSha256(forged.getPath());

        assertEquals("a copied cert with a forged .SF signature must yield no cert",
                null, certSha256);
    }

    // ---- helpers: real-zip mutations of the real fixture ----

    /**
     * Issue #9, item 5: rewrites the zip flipping one byte of the .SF body —
     * the META-INF cert (.RSA) is kept, but the .SF signature no longer
     * verifies, exactly a "copied cert / forged signature" archive.
     */
    private static File forgeMetaInfSf(File apk) throws IOException {
        File out = File.createTempFile("jukebox-forged", ".apk", apk.getParentFile());
        out.deleteOnExit();
        try (ZipFile zip = new ZipFile(apk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] content = readFully(zip, entry);
                if (entry.getName().startsWith("META-INF/") && entry.getName().endsWith(".SF")) {
                    content[content.length / 2] ^= (byte) 0xA5; // break the signature
                }
                zos.putNextEntry(new ZipEntry(entry.getName()));
                zos.write(content);
                zos.closeEntry();
            }
        }
        return out;
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