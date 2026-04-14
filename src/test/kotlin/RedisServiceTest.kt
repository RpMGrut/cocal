import me.delyfss.cocal.cache.RedisConfig
import me.delyfss.cocal.cache.RedisService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class RedisServiceTest {

    private val logger = Logger.getLogger("RedisServiceTest")

    @Test
    fun `disabled service stays unavailable and short-circuits all operations`() {
        val service = RedisService(RedisConfig(enabled = false), logger)
        service.start()
        assertFalse(service.isAvailable)

        // All methods must return safely without throwing while unavailable.
        assertFalse(service.set("k", "v").join())
        assertEquals(0L, service.delete("k").join())
        assertEquals(null, service.get("k").join())
        assertEquals(0L, service.sadd("s", "a", "b").join())
        assertEquals(emptySet<String>(), service.smembers("s").join())
        assertEquals(0L, service.publish("chan", "hi").join())

        service.stop()
    }

    @Test
    fun `real redis round trip runs when REDIS_TEST_URI is set`() {
        val uri = System.getenv("REDIS_TEST_URI") ?: return
        val service = RedisService(RedisConfig(enabled = true, uri = uri), logger)
        try {
            service.start()
            if (!service.isAvailable) return
            assertEquals(true, service.set("cocal:test", "ok").join())
            assertEquals("ok", service.get("cocal:test").join())
            assertEquals(1L, service.delete("cocal:test").join())
        } finally {
            service.stop()
        }
    }
}
