package top.ellan.mahjong.table.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelimitedFingerprintBuilderTest {
    @Test
    void optimizedPrimitiveFieldsPreserveDelimitedTextAndObjectFallbacks() {
        String fingerprint = DelimitedFingerprintBuilder.create(128)
            .field((byte) -128)
            .field((short) -32768)
            .field(Integer.MIN_VALUE)
            .field(Long.MIN_VALUE)
            .field(1.25F)
            .field(-0.0D)
            .field('Z')
            .field(true)
            .field(false)
            .field((Object) null)
            .toString();

        assertEquals("-128:-32768:-2147483648:-9223372036854775808:1.25:-0.0:Z:true:false:", fingerprint);
    }

    @Test
    void primitiveFieldsKeepRawAndEntrySeparatorSemantics() {
        String fingerprint = DelimitedFingerprintBuilder.create(32)
            .field(1)
            .entrySeparator()
            .field(false)
            .raw("|")
            .field((Object) null)
            .toString();

        assertEquals("1;false|:", fingerprint);
    }
}
