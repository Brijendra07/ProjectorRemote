package com.brijendra.projectorremote.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Holds this phone's ADB identity (RSA key + self-signed certificate).
 * The projector remembers it after pairing, so pairing is needed only once.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val mPrivateKey: PrivateKey
    private val mCertificate: Certificate

    init {
        setApi(maxOf(Build.VERSION.SDK_INT, Build.VERSION_CODES.R))
        setTimeout(8, TimeUnit.SECONDS)

        val dir = context.filesDir
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")

        var key: PrivateKey? = null
        var cert: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
            } catch (_: Exception) {
                key = null
                cert = null
            }
        }

        if (key == null || cert == null) {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=ProjectorRemote")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - DAY),
                Date(now + 3650L * DAY),
                name,
                kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val newCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(newCert.encoded)
            key = kp.private
            cert = newCert
        }

        mPrivateKey = key!!
        mCertificate = cert!!
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey

    override fun getCertificate(): Certificate = mCertificate

    override fun getDeviceName(): String = "ProjectorRemote"

    companion object {
        private const val DAY = 86_400_000L

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun get(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
