package me.delyfss.cocal.database

data class DatabaseConfig(
    val driver: DatabaseDriver = DatabaseDriver.SQLITE,
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeoutMillis: Long = 10_000L,
    val idleTimeoutMillis: Long = 600_000L,
    val maxLifetimeMillis: Long = 1_800_000L,
    val poolName: String = "cocal-db"
)

enum class DatabaseDriver(val jdbcPrefix: String, val driverClassName: String?) {
    MYSQL("jdbc:mysql://", "com.mysql.cj.jdbc.Driver"),
    MARIADB("jdbc:mariadb://", "org.mariadb.jdbc.Driver"),
    SQLITE("jdbc:sqlite:", "org.sqlite.JDBC");

    fun buildUrl(raw: String): String {
        return if (raw.startsWith("jdbc:")) raw else jdbcPrefix + raw
    }
}
