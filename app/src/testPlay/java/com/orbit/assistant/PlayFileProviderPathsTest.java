package com.orbit.assistant;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.XmlResourceParser;

import org.xmlpull.v1.XmlPullParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Play edition shares no APK directory with other apps, and loses nothing else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayFileProviderPathsTest {
    private static List<String> entries(String file) {
        Matcher m = Pattern.compile("<(\\w+-path) name=\"([^\"]+)\" path=\"([^\"]+)\"")
                .matcher(DistributionBoundaryTest.read(file));
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1) + " " + m.group(2) + " " + m.group(3));
        return out;
    }

    @Test public void playPathsAreMainPathsWithoutTheApkDirectories() {
        List<String> expected = entries("app/src/main/res/xml/file_paths.xml");
        expected.remove("cache-path orbit_updates updates/");
        expected.remove("cache-path orbit_local_component orbit-local/");
        assertEquals(expected, entries("app/src/play/res/xml/file_paths.xml"));
    }

    /**
     * The resource the Play build actually merged, read the way FileProvider reads it.
     *
     * <p>FileProvider.getUriForFile itself cannot be exercised here: Robolectric does not resolve
     * FileProvider roots in this project for any path (see FileProviderPathsTest). Reading the
     * compiled XML proves the Play overlay replaced the main file rather than sitting beside it.
     */
    @Test public void theMergedPlayResourceHasNoApkDirectory() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        List<String> paths = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.file_paths)) {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event == XmlPullParser.START_TAG && parser.getName().endsWith("-path")) {
                    paths.add(parser.getAttributeValue(null, "path"));
                }
            }
        }
        assertEquals(java.util.Collections.singletonList("orbit_picker_camera/"), paths);
    }
}
