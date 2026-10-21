package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class MediaHasherTest {

    @Test
    fun ` hashBytes matches jdk reference `() {
        val data = "album-marker-hash-test".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MediaHasher.hashBytes(data))
    }

    @Test
    fun ` hashStream equals one-shot digest `() {
        val data = ByteArray(3 * 1024 * 1024 + 123) { (it % 251).toByte() } // 跨多个 1MiB 缓冲
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MediaHasher.hashStream(ByteArrayInputStream(data)))
    }

    @Test
    fun ` hash is stable across calls `() {
        val data = "稳定".toByteArray()
        assertEquals(MediaHasher.hashBytes(data), MediaHasher.hashBytes(data))
    }

    @Test
    fun ` different content different hash `() {
        val a = MediaHasher.hashBytes("a".toByteArray())
        val b = MediaHasher.hashBytes("b".toByteArray())
        assertEquals(true, a != b)
        assertEquals(64, a.length)
    }
}
