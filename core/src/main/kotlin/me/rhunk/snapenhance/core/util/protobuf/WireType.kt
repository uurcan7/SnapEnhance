package me.rhunk.snapenhance.core.util.protobuf;

enum class WireType(val value: Int) {
    VARINT(0),
    FIXED64(1),
    CHUNK(2),
    START_GROUP(3),
    END_GROUP(4),
    FIXED32(5);

    companion object {
        fun fromValue(value: Int) = values().firstOrNull { it.value == value }
    }
}