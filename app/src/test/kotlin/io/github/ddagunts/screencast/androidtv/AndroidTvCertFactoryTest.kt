package io.github.ddagunts.screencast.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.RSAPublicKey

class AndroidTvCertFactoryTest {

    @Test fun `generated cert is RSA-2048`() {
        val m = AndroidTvCertFactory.generate()
        assertTrue("public key must be RSA", m.cert.publicKey is RSAPublicKey)
        assertEquals(2048, (m.cert.publicKey as RSAPublicKey).modulus.bitLength())
    }

    @Test fun `generated cert is v3`() {
        // X509Certificate.version is 1-based; v3 == 3.
        assertEquals(3, AndroidTvCertFactory.generate().cert.version)
    }

    @Test fun `notBefore is in the past and notAfter is well into the future`() {
        val now = System.currentTimeMillis()
        val m = AndroidTvCertFactory.generate(now)
        assertTrue("notBefore should predate now",
            m.cert.notBefore.time <= now)
        assertTrue("notAfter should be at least 1 year out",
            m.cert.notAfter.time - now > 365L * 24 * 60 * 60 * 1000)
    }

    @Test fun `subject and issuer match (self-signed)`() {
        val m = AndroidTvCertFactory.generate()
        assertEquals(m.cert.subjectX500Principal, m.cert.issuerX500Principal)
    }

    @Test fun `cert verifies under its own public key`() {
        val m = AndroidTvCertFactory.generate()
        // Throws if the signature doesn't verify.
        m.cert.verify(m.keyPair.public)
    }

    @Test fun `keypair private and cert public agree`() {
        val m = AndroidTvCertFactory.generate()
        // Compare moduli on the RSA keys — equality of PublicKey objects
        // can be implementation-dependent.
        val certPub = m.cert.publicKey as RSAPublicKey
        val pairPub = m.keyPair.public as RSAPublicKey
        assertEquals(certPub.modulus, pairPub.modulus)
        assertEquals(certPub.publicExponent, pairPub.publicExponent)
    }
}
