'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { WireFrameDecoder, aggregateFrames, decodeVarIntPrefix } = require('./wire_frames')

function encodeVarInt (value) {
  const bytes = []
  do {
    let current = value & 0x7f
    value >>>= 7
    if (value !== 0) current |= 0x80
    bytes.push(current)
  } while (value !== 0)
  return Buffer.from(bytes)
}

test('decodes split and coalesced Minecraft frames exactly', () => {
  const observed = []
  const decoder = new WireFrameDecoder('clientbound', frame => observed.push(frame))
  const first = Buffer.concat([encodeVarInt(3), Buffer.from([1, 2, 3])])
  const secondPayload = Buffer.alloc(130, 7)
  const second = Buffer.concat([encodeVarInt(secondPayload.length), secondPayload])
  const joined = Buffer.concat([first, second])

  decoder.feed(joined.subarray(0, 2))
  assert.equal(observed.length, 0)
  decoder.feed(joined.subarray(2, 8))
  assert.equal(observed.length, 1)
  decoder.feed(joined.subarray(8))
  decoder.assertComplete()

  assert.equal(decoder.totalBytes, joined.length)
  assert.deepEqual(observed.map(frame => frame.wire_bytes), [4, 132])
  assert.deepEqual(observed.map(frame => frame.length_prefix_bytes), [1, 2])
})

test('rejects malformed and oversized frame lengths', () => {
  assert.throws(() => decodeVarIntPrefix(Buffer.from([0x80, 0x80, 0x80, 0x80, 0x80])))
  const decoder = new WireFrameDecoder('serverbound', () => {}, 10)
  assert.throws(() => decoder.feed(Buffer.concat([encodeVarInt(11), Buffer.alloc(11)])))
})

test('aggregates direction, state, name, wire bytes and decoded bytes', () => {
  const result = aggregateFrames([
    { direction: 'clientbound', state: 'play', packet_name: 'keep_alive', wire_bytes: 10, decoded_bytes: 9 },
    { direction: 'clientbound', state: 'play', packet_name: 'keep_alive', wire_bytes: 11, decoded_bytes: 10 },
    { direction: 'serverbound', state: 'play', packet_name: 'keep_alive', wire_bytes: 10, decoded_bytes: 0 }
  ])
  assert.equal(result['clientbound/play/keep_alive'].packets, 2)
  assert.equal(result['clientbound/play/keep_alive'].wire_bytes, 21)
  assert.equal(result['clientbound/play/keep_alive'].decoded_bytes, 19)
  assert.equal(result['serverbound/play/keep_alive'].packets, 1)
})
