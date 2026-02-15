package app.solarma.wallet

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for SolanaRpcClient pure-logic functions:
 * - parseEndpoints
 * - parseResultObject
 * - parseResultValue
 * - SignatureStatus / ProgramAccount data classes
 * - setNetwork toggle
 */
class SolanaRpcClientTest {
    private lateinit var client: SolanaRpcClient

    @Before
    fun setUp() {
        client = SolanaRpcClient()
    }

    // ── parseEndpoints ──

    @Test
    fun `parseEndpoints splits comma-separated URLs`() {
        val result = client.parseEndpoints("https://a.com,https://b.com,https://c.com")
        assertEquals(3, result.size)
        assertEquals("https://a.com", result[0])
        assertEquals("https://b.com", result[1])
        assertEquals("https://c.com", result[2])
    }

    @Test
    fun `parseEndpoints handles single URL`() {
        val result = client.parseEndpoints("https://only.com")
        assertEquals(1, result.size)
        assertEquals("https://only.com", result[0])
    }

    @Test
    fun `parseEndpoints returns empty for empty string`() {
        val result = client.parseEndpoints("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseEndpoints trims whitespace`() {
        val result = client.parseEndpoints("  https://a.com  , https://b.com  ")
        assertEquals(2, result.size)
        assertEquals("https://a.com", result[0])
        assertEquals("https://b.com", result[1])
    }

    @Test
    fun `parseEndpoints skips empty segments`() {
        val result = client.parseEndpoints("https://a.com,,https://b.com,")
        assertEquals(2, result.size)
    }

    @Test
    fun `parseEndpoints handles only commas`() {
        val result = client.parseEndpoints(",,,")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseEndpoints handles whitespace-only segments`() {
        val result = client.parseEndpoints("  ,  ,  ")
        assertTrue(result.isEmpty())
    }

    // ── parseResultObject ──

    @Test
    fun `parseResultObject returns result for valid response`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{"value":12345}}"""
        val result = client.parseResultObject(response)
        assertEquals(12345, result.getInt("value"))
    }

    @Test(expected = Exception::class)
    fun `parseResultObject throws on error response`() {
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid request"}}"""
        client.parseResultObject(response)
    }

    @Test
    fun `parseResultObject error message is preserved`() {
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Custom error msg"}}"""
        try {
            client.parseResultObject(response)
            fail("Expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Custom error msg"))
        }
    }

    @Test
    fun `parseResultObject with nested result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{"value":{"blockhash":"abc123"}}}"""
        val result = client.parseResultObject(response)
        val value = result.getJSONObject("value")
        assertEquals("abc123", value.getString("blockhash"))
    }

    @Test(expected = Exception::class)
    fun `parseResultObject throws on malformed JSON`() {
        client.parseResultObject("not json at all")
    }

    // ── parseResultValue ──

    @Test
    fun `parseResultValue returns string result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":"signatureHere"}"""
        val result = client.parseResultValue(response)
        assertEquals("signatureHere", result)
    }

    @Test
    fun `parseResultValue returns numeric result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":42}"""
        val result = client.parseResultValue(response)
        assertEquals(42, result)
    }

    @Test
    fun `parseResultValue returns null for null result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":null}"""
        val result = client.parseResultValue(response)
        assertEquals(JSONObject.NULL, result)
    }

    @Test(expected = Exception::class)
    fun `parseResultValue throws on error response`() {
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Server error"}}"""
        client.parseResultValue(response)
    }

    @Test
    fun `parseResultValue returns boolean result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":true}"""
        val result = client.parseResultValue(response)
        assertEquals(true, result)
    }

    @Test
    fun `parseResultValue with no result key returns null`() {
        val response = """{"jsonrpc":"2.0","id":1}"""
        val result = client.parseResultValue(response)
        assertNull(result)
    }

    // ── setNetwork ──

    @Test
    fun `setNetwork toggles state without throwing`() {
        client.setNetwork(true)
        client.setNetwork(false)
        client.setNetwork(true)
        // No exceptions — just state toggle
    }

    // ── Data classes ──

    @Test
    fun `SignatureStatus data class equality`() {
        val a = SignatureStatus("confirmed", null)
        val b = SignatureStatus("confirmed", null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SignatureStatus with error`() {
        val status = SignatureStatus("processed", """{"InstructionError":[0,{"Custom":1}]}""")
        assertEquals("processed", status.confirmationStatus)
        assertNotNull(status.err)
    }

    @Test
    fun `SignatureStatus null fields`() {
        val status = SignatureStatus(null, null)
        assertNull(status.confirmationStatus)
        assertNull(status.err)
    }

    @Test
    fun `SignatureStatus copy works`() {
        val original = SignatureStatus("confirmed", null)
        val copy = original.copy(err = "some error")
        assertEquals("confirmed", copy.confirmationStatus)
        assertEquals("some error", copy.err)
    }

    @Test
    fun `ProgramAccount data class equality`() {
        val a = ProgramAccount("pubkey1", "base64data1")
        val b = ProgramAccount("pubkey1", "base64data1")
        assertEquals(a, b)
    }

    @Test
    fun `ProgramAccount different data not equal`() {
        val a = ProgramAccount("pubkey1", "data1")
        val b = ProgramAccount("pubkey1", "data2")
        assertNotEquals(a, b)
    }

    @Test
    fun `ProgramAccount destructuring`() {
        val pa = ProgramAccount("pk", "d64")
        val (pubkey, data) = pa
        assertEquals("pk", pubkey)
        assertEquals("d64", data)
    }

    @Test
    fun `ProgramAccount toString contains fields`() {
        val pa = ProgramAccount("testKey", "encodedData")
        val str = pa.toString()
        assertTrue(str.contains("testKey"))
        assertTrue(str.contains("encodedData"))
    }

    @Test
    fun `SignatureStatus toString contains fields`() {
        val ss = SignatureStatus("finalized", null)
        val str = ss.toString()
        assertTrue(str.contains("finalized"))
    }

    // ── parseResultObject edge cases ──

    @Test
    fun `parseResultObject with empty result object`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        val result = client.parseResultObject(response)
        assertEquals(0, result.length())
    }

    @Test
    fun `parseResultValue with array result`() {
        val response = """{"jsonrpc":"2.0","id":1,"result":[1,2,3]}"""
        val result = client.parseResultValue(response)
        assertNotNull(result)
    }

    @Test
    fun `parseResultObject error without message falls back`() {
        val response = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600}}"""
        try {
            client.parseResultObject(response)
            fail("Expected exception")
        } catch (e: Exception) {
            // optString default is "RPC error"
            assertTrue(e.message!!.contains("RPC error"))
        }
    }
}
