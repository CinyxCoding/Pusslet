package se.pusslet.data

import java.io.File

/** Host tests run with the module directory as working directory. */
actual fun readFixture(name: String): String = File("src/commonTest/resources/fixtures/$name").readText(Charsets.UTF_8)
