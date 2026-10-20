package com.quartz.downloader
import org.junit.Assert.*
import org.junit.Test
class UpdateVersionsTest {
    @Test fun patchIsComparedNumerically(){assertTrue(UpdateVersions.compare("0.3.10","0.3.9")>0)}
    @Test fun minorAndMajorAreCompared(){assertTrue(UpdateVersions.compare("1.0.0","0.99.99")>0);assertTrue(UpdateVersions.compare("0.4.0","0.3.99")>0)}
    @Test fun equalVersionIsNotAnUpdate(){assertEquals(0,UpdateVersions.compare("0.3.4","0.3.4"))}
    @Test fun downgradeIsRejected(){assertTrue(UpdateVersions.compare("0.3.3","0.3.4")<0)}
    @Test fun nonStableVersionsAreRejected(){for(value in listOf("v0.3.4","0.3.4-beta","../update","0.3","9999999.0.0"))assertFalse(UpdateVersions.valid(value))}
}
