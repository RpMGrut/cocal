import me.delyfss.cocal.database.DatabaseConfig
import me.delyfss.cocal.database.DatabaseDriver
import me.delyfss.cocal.database.DatabaseService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger

class DatabaseServiceTest {

    private lateinit var tempDir: File
    private lateinit var service: DatabaseService

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("cocal-db-test").toFile()
        val dbFile = File(tempDir, "test.db")
        val config = DatabaseConfig(
            driver = DatabaseDriver.SQLITE,
            url = dbFile.absolutePath,
            poolName = "cocal-test"
        )
        service = DatabaseService(config, Logger.getLogger("DatabaseServiceTest"))
        service.start()
    }

    @AfterEach
    fun tearDown() {
        service.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun `pool starts and executes a round trip`() {
        assertTrue(service.isStarted)

        service.withConnection { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            }
        }

        service.withConnection { connection ->
            connection.prepareStatement("INSERT INTO thing(name) VALUES (?)").use { stmt ->
                stmt.setString(1, "alpha")
                stmt.executeUpdate()
            }
        }

        val name = service.withConnection { connection ->
            connection.prepareStatement("SELECT name FROM thing WHERE id = 1").use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getString("name")
            }
        }
        assertEquals("alpha", name)
    }

    @Test
    fun `transaction rolls back on exception`() {
        service.withConnection { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            service.transaction { connection ->
                connection.prepareStatement("INSERT INTO items(label) VALUES ('one')").use { it.executeUpdate() }
                connection.prepareStatement("INSERT INTO items(label) VALUES ('two')").use { it.executeUpdate() }
                error("simulated failure")
            }
        }

        val count = service.withConnection { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM items").use { stmt ->
                val rs = stmt.executeQuery()
                rs.next()
                rs.getInt(1)
            }
        }
        assertEquals(0, count)
    }

    @Test
    fun `stopping the pool marks it as not started`() {
        service.stop()
        assertFalse(service.isStarted)
    }
}
