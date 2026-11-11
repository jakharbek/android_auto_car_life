package com.jakharbek.monzaauto.aa

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate and key the app presents to Android Auto as a head unit.
 * Taken from files imported in the app (files/hu/), otherwise from the APK assets (assets/hu/).
 */
class HuCredentials(val cert: X509Certificate, val key: PrivateKey) {

    fun sslContext(): SSLContext {
        // Always offers the one certificate, whatever the server asks for.
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = emptyArray<String>()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
        }
        // The phone's certificate is not checked: the connection is on the phone itself (loopback).
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(trustAll), null)
        return ctx
    }

    companion object {
        private const val ALIAS = "hu"
        private const val DIR = "hu"

        fun load(context: Context): HuCredentials? {
            val local = File(context.filesDir, DIR)
            val texts = ArrayList<String>()
            local.listFiles()?.sortedBy { it.name }?.forEach { texts += it.readText() }
            if (texts.isEmpty()) {
                try {
                    context.assets.list(DIR)?.sorted()?.forEach { name ->
                        texts += context.assets.open("$DIR/$name").bufferedReader().readText()
                    }
                } catch (_: Exception) {
                }
            }
            return fromPem(texts.joinToString("\n"))
        }

        /** Saves imported PEM text if it contains both a certificate and a private key. */
        fun import(context: Context, pemText: String): Boolean {
            fromPem(pemText) ?: return false
            val dir = File(context.filesDir, DIR)
            dir.deleteRecursively()
            dir.mkdirs()
            File(dir, "hu.pem").writeText(pemText)
            return true
        }

        fun fromPem(text: String): HuCredentials? {
            val blocks = Regex("-----BEGIN ([A-Z ]+)-----([\\s\\S]*?)-----END \\1-----").findAll(text)
            var cert: X509Certificate? = null
            var key: PrivateKey? = null
            for (b in blocks) {
                val kind = b.groupValues[1]
                val der = try {
                    Base64.getMimeDecoder().decode(b.groupValues[2].trim())
                } catch (_: Exception) {
                    continue
                }
                try {
                    when (kind) {
                        "CERTIFICATE" -> if (cert == null) {
                            cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
                        }
                        "PRIVATE KEY" -> if (key == null) {
                            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
                        }
                        "RSA PRIVATE KEY" -> if (key == null) {
                            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)))
                        }
                    }
                } catch (_: Exception) {
                }
            }
            val c = cert ?: return null
            val k = key ?: return null
            return HuCredentials(c, k)
        }

        /** Wraps a PKCS#1 RSAPrivateKey into a PKCS#8 PrivateKeyInfo. */
        fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
            val algId = byteArrayOf(
                0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val octet = der(0x04, pkcs1)
            return der(0x30, version + algId + octet)
        }

        private fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
