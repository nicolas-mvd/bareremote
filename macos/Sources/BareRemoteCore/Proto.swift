import Foundation

/// A bounded implementation of the protobuf wire types used by Android TV Remote v2.
/// Protocol field numbers are encoded directly; no generated protobuf runtime is needed.
enum Proto {
    static let maxFrame = 64 * 1024
    static let maxFields = 4096

    final class Writer {
        private var data = Data()

        @discardableResult
        func number(_ field: Int, _ value: UInt64) throws -> Writer {
            try tag(field, 0)
            data.append(Proto.varint(value))
            return self
        }

        @discardableResult
        func bytes(_ field: Int, _ value: Data) throws -> Writer {
            guard value.count <= maxFrame else { throw TVProtocolError.malformed("Protobuf value is too large") }
            try tag(field, 2)
            data.append(Proto.varint(UInt64(value.count)))
            data.append(value)
            return self
        }

        @discardableResult
        func text(_ field: Int, _ value: String) throws -> Writer {
            try bytes(field, Data(value.utf8))
        }

        @discardableResult
        func message(_ field: Int, _ value: Writer) throws -> Writer {
            try bytes(field, value.build())
        }

        func build() throws -> Data {
            guard data.count <= maxFrame else { throw TVProtocolError.malformed("Protocol message is too large") }
            return data
        }

        private func tag(_ field: Int, _ wire: UInt64) throws {
            guard field > 0, field <= 0x1fff_ffff, data.count <= maxFrame else {
                throw TVProtocolError.malformed("Invalid protobuf field")
            }
            data.append(Proto.varint(UInt64(field) << 3 | wire))
        }
    }

    struct Message {
        struct Field {
            let number: Int
            let wire: UInt64
            let value: UInt64
            let data: Data?
        }
        let fields: [Field]

        func has(_ field: Int) -> Bool { fields.contains { $0.number == field } }

        func integer(_ field: Int, default fallback: UInt64 = 0) throws -> UInt64 {
            guard let value = fields.last(where: { $0.number == field }) else { return fallback }
            guard value.wire == 0 else { throw TVProtocolError.malformed("Wrong wire type for integer") }
            return value.value
        }

        func bytes(_ field: Int) throws -> Data? {
            guard let value = fields.last(where: { $0.number == field }) else { return nil }
            guard value.wire == 2 else { throw TVProtocolError.malformed("Wrong wire type for message") }
            return value.data
        }

        func message(_ field: Int) throws -> Message {
            guard let value = try bytes(field) else { throw TVProtocolError.malformed("Missing protocol message") }
            return try Proto.parse(value)
        }
    }

    static func parse(_ data: Data) throws -> Message {
        guard data.count <= maxFrame else { throw TVProtocolError.malformed("Protocol message is too large") }
        let bytes = [UInt8](data)
        var offset = 0
        var fields: [Message.Field] = []
        while offset < bytes.count {
            guard fields.count < maxFields else { throw TVProtocolError.malformed("Too many protocol fields") }
            let tag = try readVarint(bytes, offset: &offset)
            guard tag > 0, tag <= UInt64(UInt32.max), tag >> 3 > 0 else {
                throw TVProtocolError.malformed("Invalid protobuf tag")
            }
            let wire = tag & 7
            var value: UInt64 = 0
            var contents: Data?
            switch wire {
            case 0:
                value = try readVarint(bytes, offset: &offset)
            case 1, 5:
                let count = wire == 1 ? 8 : 4
                guard bytes.count - offset >= count else { throw TVProtocolError.malformed("Truncated fixed-width field") }
                offset += count
            case 2:
                let count = try readVarint(bytes, offset: &offset)
                guard count <= UInt64(bytes.count - offset) else { throw TVProtocolError.malformed("Truncated protobuf value") }
                contents = Data(bytes[offset..<(offset + Int(count))])
                offset += Int(count)
            default:
                throw TVProtocolError.malformed("Unsupported protobuf wire type")
            }
            fields.append(.init(number: Int(tag >> 3), wire: wire, value: value, data: contents))
        }
        return Message(fields: fields)
    }

    static func varint(_ value: UInt64) -> Data {
        var remaining = value
        var result = Data()
        while remaining > 0x7f {
            result.append(UInt8(remaining & 0x7f) | 0x80)
            remaining >>= 7
        }
        result.append(UInt8(remaining))
        return result
    }

    static func readVarint(_ bytes: [UInt8], offset: inout Int) throws -> UInt64 {
        var result: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard offset < bytes.count else { throw TVProtocolError.malformed("Truncated varint") }
            let byte = bytes[offset]
            offset += 1
            guard shift != 63 || byte & 0xfe == 0 else { throw TVProtocolError.malformed("Varint overflow") }
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
        }
        throw TVProtocolError.malformed("Varint overflow")
    }

    static func frame(_ data: Data) throws -> Data {
        guard !data.isEmpty, data.count <= maxFrame else { throw TVProtocolError.malformed("Invalid frame length") }
        var result = varint(UInt64(data.count))
        result.append(data)
        return result
    }
}
