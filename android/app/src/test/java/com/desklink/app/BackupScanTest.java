package com.desklink.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The folder each photo or video goes in on the PC, from where it is on the phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BackupScanTest {
    @Test
    public void thePhonesFoldersAsTheyAre() {
        assertEquals("DCIM/Camera", BackupScan.folderFor("DCIM/Camera/", true, "SD card"));
        assertEquals("Pictures/Screenshots", BackupScan.folderFor("Pictures/Screenshots/", true, "SD card"));
        assertEquals("DCIM/Screenshots", BackupScan.folderFor("/DCIM/Screenshots", true, "SD card"));
        assertEquals("", BackupScan.folderFor("", true, "SD card"));
        assertEquals("", BackupScan.folderFor(null, true, "SD card"));
    }

    @Test
    public void anAppsOwnMediaFolderLosesItsStart() {
        assertEquals("WhatsApp/Media/WhatsApp Images/Sent",
                BackupScan.folderFor("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/", true, "SD card"));
        assertEquals("", BackupScan.folderFor("Android/media/com.example/", true, "SD card"));
    }

    @Test
    public void aMemoryCardHasAFolderOfItsOwn() {
        assertEquals("SD card/DCIM/Camera", BackupScan.folderFor("DCIM/Camera/", false, "SD card"));
        assertEquals("Tarjeta SD", BackupScan.folderFor("", false, "Tarjeta SD"));
    }

    @Test
    public void beforeAndroid10FromTheFilesPath() {
        String storage = "/storage/emulated/0";
        assertEquals("DCIM/Camera", BackupScan.folderFromPath("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", storage, "SD card"));
        assertEquals("", BackupScan.folderFromPath("/storage/emulated/0/IMG_1.jpg", storage, "SD card"));
        assertEquals("WhatsApp/Media/WhatsApp Images", BackupScan.folderFromPath(
                "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", storage, "SD card"));
        assertEquals("SD card/DCIM/Camera", BackupScan.folderFromPath("/storage/1A2B-3C4D/DCIM/Camera/IMG_2.jpg", storage, "SD card"));
    }
}
