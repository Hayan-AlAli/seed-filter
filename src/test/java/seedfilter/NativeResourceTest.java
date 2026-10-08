package seedfilter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NativeResourceTest {
    @Test
    void picksLibraryForEachSupportedPlatform() {
        assertEquals(Optional.of("natives/windows-x64/seedfilter.dll"), Native.resourceFor("Windows 11", "amd64"));
        assertEquals(Optional.of("natives/windows-arm64/seedfilter.dll"), Native.resourceFor("Windows 11", "aarch64"));
        assertEquals(Optional.of("natives/linux-x64/libseedfilter.so"), Native.resourceFor("Linux", "amd64"));
        assertEquals(Optional.of("natives/linux-arm64/libseedfilter.so"), Native.resourceFor("Linux", "aarch64"));
        assertEquals(Optional.of("natives/macos-x64/libseedfilter.dylib"), Native.resourceFor("Mac OS X", "x86_64"));
        assertEquals(Optional.of("natives/macos-arm64/libseedfilter.dylib"), Native.resourceFor("Mac OS X", "aarch64"));
    }

    @Test
    void unsupportedPlatformsGetNothing() {
        assertEquals(Optional.empty(), Native.resourceFor("FreeBSD", "amd64"));
        assertEquals(Optional.empty(), Native.resourceFor("Linux", "x86"));
        assertEquals(Optional.empty(), Native.resourceFor("Linux", "riscv64"));
    }
}
