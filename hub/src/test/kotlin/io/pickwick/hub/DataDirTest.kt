package io.pickwick.hub

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The startup preflight.
 *
 * These assertions exist because of a real deployment: a bind-mounted volume
 * arrived as mode 000 with a NAS ACL, the hub died on its first write, and the
 * container restarted into the same stack trace every few seconds. The log
 * never said "the volume is not writable" — it said FileOutputStream.open0.
 *
 * What is being pinned here is not that the check works, but that a failure
 * says something a person can act on. So the tests assert on the *content* of
 * the message, not merely that one was produced.
 */
class DataDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val writable: (File) -> Boolean = { true }
    private val denied: (File) -> Boolean = { false }

    @Test
    fun aWritableDirectoryIsNoProblem() {
        assertNull(dataDirProblem(tmp.newFolder("data"), writable))
    }

    @Test
    fun aMissingDirectoryIsCreatedRatherThanRefused() {
        // A fresh install has no data folder. Creating it is the whole job, so
        // "not there yet" must not be treated as a failure.
        val fresh = File(tmp.root, "not-created-yet")
        assertNull(dataDirProblem(fresh, writable))
        assertTrue("the preflight should have created it", fresh.isDirectory)
    }

    @Test
    fun aFileWhereTheDirectoryShouldBeIsReported() {
        val notADir = tmp.newFile("data")
        val problem = dataDirProblem(notADir, writable)
        assertNotNull(problem)
        assertTrue(problem!!.contains("not a directory"))
    }

    @Test
    fun anUnwritableDirectoryExplainsItselfInsteadOfThrowing() {
        val problem = dataDirProblem(tmp.newFolder("data"), denied)
        assertNotNull("an unwritable volume must be caught here, not by the first write", problem)
    }

    @Test
    fun theUnwritableMessageNamesThePathAndTheLikelyCause() {
        // The failure a parent actually meets is a Docker bind mount whose
        // ownership belongs to the host. A message that says only "not
        // writable" sends them looking inside the container, which is the one
        // place the cause is not.
        val dir = tmp.newFolder("data")
        val problem = dataDirProblem(dir, denied)!!

        assertTrue("must name the path", problem.contains(dir.absolutePath))
        assertTrue("must mention the mount", problem.contains("mount"))
        assertTrue("must mention ownership", problem.contains("ownership"))
        assertTrue("must point somewhere with the fix", problem.contains("docs/HUB.md"))
    }

    @Test
    fun theProbeActuallyWritesRatherThanAskingTheFileSystemNicely() {
        // The default probe must not be File.canWrite(). That consults the
        // permission bits, and the deployment this test exists for had bits
        // that said one thing while an ACL did another. Creating a file is the
        // only answer that is not a guess.
        val dir = tmp.newFolder("data")
        assertNull(dataDirProblem(dir))
        assertTrue(
            "the probe must leave nothing behind",
            dir.listFiles().orEmpty().none { it.name.startsWith(".pickwick-write-test") }
        )
    }
}
