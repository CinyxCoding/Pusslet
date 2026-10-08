package se.pusslet.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/** Simulator tests run from the module directory when started by Gradle. */
@OptIn(ExperimentalForeignApi::class)
actual fun readFixture(name: String): String {
    val path = NSFileManager.defaultManager.currentDirectoryPath + "/src/commonTest/resources/fixtures/" + name
    return requireNotNull(NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)) { "Fixture not found: $path" }
}
