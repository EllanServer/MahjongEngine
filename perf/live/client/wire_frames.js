'use strict'

const MAX_VARINT_BYTES = 5
const DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024

function decodeVarIntPrefix (buffer) {
  let value = 0
  for (let index = 0; index < Math.min(buffer.length, MAX_VARINT_BYTES); index++) {
    const byte = buffer[index]
    value |= (byte & 0x7f) << (7 * index)
    if ((byte & 0x80) === 0) {
      return { complete: true, value: value >>> 0, bytes: index + 1 }
    }
  }
  if (buffer.length >= MAX_VARINT_BYTES) {
    throw new Error('Minecraft frame length VarInt exceeds five bytes')
  }
  return { complete: false }
}

class WireFrameDecoder {
  constructor (direction, onFrame, maxFrameBytes = DEFAULT_MAX_FRAME_BYTES) {
    this.direction = direction
    this.onFrame = onFrame
    this.maxFrameBytes = maxFrameBytes
    this.pending = Buffer.alloc(0)
    this.totalBytes = 0
    this.frameCount = 0
  }

  feed (chunk) {
    if (!(chunk instanceof Uint8Array)) {
      throw new TypeError('wire data must be a Buffer or Uint8Array')
    }
    const bytes = Buffer.from(chunk)
    this.totalBytes += bytes.length
    this.pending = this.pending.length === 0 ? bytes : Buffer.concat([this.pending, bytes])

    while (this.pending.length > 0) {
      const prefix = decodeVarIntPrefix(this.pending)
      if (!prefix.complete) return
      if (prefix.value > this.maxFrameBytes) {
        throw new Error(`Minecraft frame is too large: ${prefix.value} bytes`)
      }
      const wireBytes = prefix.bytes + prefix.value
      if (this.pending.length < wireBytes) return
      this.frameCount++
      this.onFrame({
        direction: this.direction,
        sequence: this.frameCount,
        observed_monotonic_ns: process.hrtime.bigint().toString(),
        wire_bytes: wireBytes,
        frame_payload_bytes: prefix.value,
        length_prefix_bytes: prefix.bytes
      })
      this.pending = this.pending.subarray(wireBytes)
    }
  }

  assertComplete () {
    if (this.pending.length !== 0) {
      throw new Error(`${this.direction} ended with ${this.pending.length} incomplete wire bytes`)
    }
  }
}

function aggregateFrames (frames) {
  const aggregate = {}
  for (const frame of frames) {
    const state = frame.state || '<unattributed>'
    const name = frame.packet_name || '<unattributed>'
    const key = `${frame.direction}/${state}/${name}`
    const entry = aggregate[key] || {
      direction: frame.direction,
      state,
      packet_name: name,
      packets: 0,
      wire_bytes: 0,
      decoded_bytes: 0
    }
    entry.packets++
    entry.wire_bytes += frame.wire_bytes
    entry.decoded_bytes += frame.decoded_bytes || 0
    aggregate[key] = entry
  }
  return Object.fromEntries(Object.entries(aggregate).sort(([left], [right]) => left.localeCompare(right)))
}

module.exports = {
  WireFrameDecoder,
  aggregateFrames,
  decodeVarIntPrefix
}
