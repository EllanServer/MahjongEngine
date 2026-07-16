#!/usr/bin/env node
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const mc = require('minecraft-protocol')
const packageMetadata = require('minecraft-protocol/package.json')
const { WireFrameDecoder, aggregateFrames } = require('./wire_frames')

function parseArguments (argv) {
  const values = {
    host: '127.0.0.1',
    port: 25565,
    version: '1.20.1',
    username: 'MahjongPerfBot',
    warmupSeconds: 5,
    durationSeconds: 30,
    outputDir: null,
    connectTimeoutSeconds: 30,
    startupCommand: null,
    commandTriggerFile: null,
    scenarioReadyPattern: null,
    scenarioTimeoutSeconds: 60,
    stateCommand: '/mahjong state',
    statePollSeconds: 0.5
  }
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const value = argv[index + 1]
    if (!flag.startsWith('--') || value === undefined) throw new Error(`Invalid argument near ${flag}`)
    switch (flag) {
      case '--host': values.host = value; break
      case '--port': values.port = Number.parseInt(value, 10); break
      case '--version': values.version = value; break
      case '--username': values.username = value; break
      case '--warmup-seconds': values.warmupSeconds = Number.parseFloat(value); break
      case '--duration-seconds': values.durationSeconds = Number.parseFloat(value); break
      case '--output-dir': values.outputDir = path.resolve(value); break
      case '--connect-timeout-seconds': values.connectTimeoutSeconds = Number.parseFloat(value); break
      case '--startup-command': values.startupCommand = value; break
      case '--command-trigger-file': values.commandTriggerFile = path.resolve(value); break
      case '--scenario-ready-pattern': values.scenarioReadyPattern = value; break
      case '--scenario-timeout-seconds': values.scenarioTimeoutSeconds = Number.parseFloat(value); break
      case '--state-command': values.stateCommand = value; break
      case '--state-poll-seconds': values.statePollSeconds = Number.parseFloat(value); break
      default: throw new Error(`Unknown argument: ${flag}`)
    }
  }
  if (!values.outputDir) throw new Error('--output-dir is required')
  if (!Number.isInteger(values.port) || values.port < 1 || values.port > 65535) throw new Error('Invalid port')
  if (!(values.durationSeconds > 0)) throw new Error('duration must be positive')
  if (!(values.warmupSeconds >= 0)) throw new Error('warmup must not be negative')
  if (!(values.connectTimeoutSeconds > 0)) throw new Error('connect timeout must be positive')
  if (!(values.scenarioTimeoutSeconds > 0)) throw new Error('scenario timeout must be positive')
  if (!(values.statePollSeconds > 0)) throw new Error('state poll interval must be positive')
  if (values.startupCommand && (!values.commandTriggerFile || !values.scenarioReadyPattern)) {
    throw new Error('--startup-command requires --command-trigger-file and --scenario-ready-pattern')
  }
  if (!values.startupCommand && (values.commandTriggerFile || values.scenarioReadyPattern)) {
    throw new Error('scenario trigger/pattern require --startup-command')
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(values.username)) throw new Error('username is not a valid offline Minecraft name')
  return values
}

class ProtocolMeter {
  constructor () {
    this.frames = []
    this.unassignedInbound = []
    this.outboundDescriptors = []
    this.socket = null
    this.inbound = new WireFrameDecoder('clientbound', frame => {
      this.frames.push(frame)
      this.unassignedInbound.push(frame)
    })
    this.outbound = new WireFrameDecoder('serverbound', frame => {
      const descriptor = this.outboundDescriptors.shift()
      if (descriptor) Object.assign(frame, descriptor)
      this.frames.push(frame)
    })
    this.baseline = { read: 0, written: 0, socketRead: 0, socketWritten: 0 }
  }

  attachSocket (socket) {
    this.socket = socket
    socket.on('data', chunk => this.inbound.feed(chunk))
    const originalWrite = socket.write
    const meter = this
    socket.write = function (chunk, ...args) {
      const encoding = typeof args[0] === 'string' ? args[0] : undefined
      meter.outbound.feed(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, encoding))
      return originalWrite.call(this, chunk, ...args)
    }
  }

  noteOutbound (packetName, state) {
    this.outboundDescriptors.push({ packet_name: packetName, state: String(state) })
  }

  noteInbound (metadata, fullBuffer) {
    const frame = this.unassignedInbound.shift()
    if (!frame) throw new Error(`No wire frame available for inbound packet ${metadata.name}`)
    frame.packet_name = metadata.name
    frame.state = String(metadata.state)
    frame.decoded_bytes = Buffer.isBuffer(fullBuffer) ? fullBuffer.length : 0
  }

  beginMeasurement () {
    if (this.unassignedInbound.length !== 0 || this.outboundDescriptors.length !== 0) {
      throw new Error(
        `Cannot start measurement with pending attribution: inbound=${this.unassignedInbound.length}, ` +
        `outbound=${this.outboundDescriptors.length}`
      )
    }
    this.frames = []
    this.baseline = {
      read: this.inbound.totalBytes,
      written: this.outbound.totalBytes,
      socketRead: this.socket?.bytesRead || 0,
      socketWritten: this.socket?.bytesWritten || 0
    }
  }

  snapshot () {
    this.inbound.assertComplete()
    this.outbound.assertComplete()
    const socketBytes = this.socket
      ? {
          read: this.socket.bytesRead - this.baseline.socketRead,
          written: this.socket.bytesWritten - this.baseline.socketWritten
        }
      : { read: 0, written: 0 }
    return {
      frames: this.frames,
      aggregate: aggregateFrames(this.frames),
      socketBytes,
      decoderBytes: {
        read: this.inbound.totalBytes - this.baseline.read,
        written: this.outbound.totalBytes - this.baseline.written
      },
      unassignedInbound: this.unassignedInbound.length,
      unassignedOutboundDescriptors: this.outboundDescriptors.length
    }
  }
}

function writeJsonAtomic (file, value) {
  const temporary = `${file}.tmp-${process.pid}`
  fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
  fs.renameSync(temporary, file)
}

function componentText (value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') {
    try {
      return componentText(JSON.parse(value))
    } catch {
      return value
    }
  }
  if (Array.isArray(value)) return value.map(componentText).join('')
  if (typeof value !== 'object') return String(value)
  const own = typeof value.text === 'string' ? value.text : ''
  const translated = typeof value.translate === 'string'
    ? `${value.translate}${value.with ? ` ${componentText(value.with)}` : ''}`
    : ''
  return `${own}${translated}${componentText(value.extra)}`
}

function jsonWithBigInt (_key, value) {
  return typeof value === 'bigint' ? value.toString() : value
}

async function run () {
  const options = parseArguments(process.argv.slice(2))
  fs.mkdirSync(options.outputDir, { recursive: true })
  const meter = new ProtocolMeter()
  let expectedEnd = false
  let joined = false
  let endReason = null
  let failure = null
  let joinedAt = null
  let scenarioReadyAt = null
  let scenarioReadyText = null
  let measurementStartedAt = null
  let measurementEndedAt = null

  class MeteredClient extends mc.Client {
    setSocket (socket) {
      meter.attachSocket(socket)
      return super.setSocket(socket)
    }

    write (name, params) {
      meter.noteOutbound(name, this.state)
      return super.write(name, params)
    }

    writeRaw (buffer) {
      meter.noteOutbound('<raw>', this.state)
      return super.writeRaw(buffer)
    }
  }

  const startedAt = new Date().toISOString()
  const chatLog = path.join(options.outputDir, 'chat.jsonl')
  const client = mc.createClient({
    host: options.host,
    port: options.port,
    username: options.username,
    auth: 'offline',
    version: options.version,
    keepAlive: true,
    disableChatSigning: true,
    hideErrors: true,
    Client: MeteredClient
  })

  const completion = new Promise((resolve, reject) => {
    let scenarioPoll = null
    let scenarioTimeout = null
    let triggerPoll = null
    let measurementTimer = null
    let shutdownTimer = null
    const readyPattern = options.scenarioReadyPattern ? new RegExp(options.scenarioReadyPattern, 'i') : null

    function clearScenarioTimers () {
      if (scenarioPoll) clearInterval(scenarioPoll)
      if (scenarioTimeout) clearTimeout(scenarioTimeout)
      if (triggerPoll) clearInterval(triggerPoll)
    }

    function startMeasurementLifecycle () {
      clearScenarioTimers()
      setTimeout(() => {
        try {
          meter.beginMeasurement()
          measurementStartedAt = new Date().toISOString()
          writeJsonAtomic(path.join(options.outputDir, 'measurement-ready.json'), {
            schema_version: 1,
            ready: true,
            measurement_started_at: measurementStartedAt,
            configured_duration_seconds: options.durationSeconds
          })
          measurementTimer = setTimeout(() => {
            expectedEnd = true
            measurementEndedAt = new Date().toISOString()
            client.end('benchmark-complete')
            shutdownTimer = setTimeout(() => client.socket?.destroy(), 5000)
            shutdownTimer.unref()
          }, options.durationSeconds * 1000)
        } catch (error) {
          reject(error)
          client.socket?.destroy()
        }
      }, options.warmupSeconds * 1000)
    }

    function observeChat (kind, event) {
      const text = componentText(event.formattedMessage || event.unsignedContent || event.plainMessage || event)
      fs.appendFileSync(
        chatLog,
        `${JSON.stringify({ observed_at: new Date().toISOString(), kind, text, event }, jsonWithBigInt)}\n`,
        'utf8'
      )
      if (readyPattern && !scenarioReadyAt && readyPattern.test(text)) {
        scenarioReadyAt = new Date().toISOString()
        scenarioReadyText = text
        writeJsonAtomic(path.join(options.outputDir, 'scenario-ready.json'), {
          schema_version: 1,
          ready: true,
          scenario_ready_at: scenarioReadyAt,
          matched_text: text,
          ready_pattern: options.scenarioReadyPattern,
          startup_command: options.startupCommand
        })
        startMeasurementLifecycle()
      }
    }

    client.on('systemChat', event => observeChat('system', event))
    client.on('playerChat', event => observeChat('player', event))

    const connectTimeout = setTimeout(() => {
      reject(new Error(`Protocol client did not reach PLAY within ${options.connectTimeoutSeconds}s`))
      client.socket?.destroy()
    }, options.connectTimeoutSeconds * 1000)

    client.on('packet', (_data, metadata, _buffer, fullBuffer) => {
      try {
        meter.noteInbound(metadata, fullBuffer)
      } catch (error) {
        reject(error)
        client.socket?.destroy()
      }
    })

    client.on('position', packet => {
      if (Number.isInteger(packet.teleportId)) {
        client.write('teleport_confirm', { teleportId: packet.teleportId })
      }
    })

    client.once('playerJoin', () => {
      clearTimeout(connectTimeout)
      joined = true
      joinedAt = new Date().toISOString()
      writeJsonAtomic(path.join(options.outputDir, 'ready.json'), {
        schema_version: 1,
        ready: true,
        joined_at: joinedAt,
        username: options.username,
        minecraft_version: options.version
      })
      if (!options.startupCommand) {
        scenarioReadyAt = joinedAt
        startMeasurementLifecycle()
        return
      }
      scenarioTimeout = setTimeout(() => {
        reject(new Error(`Scenario did not become ready within ${options.scenarioTimeoutSeconds}s`))
        client.socket?.destroy()
      }, options.scenarioTimeoutSeconds * 1000)
      triggerPoll = setInterval(() => {
        if (!fs.existsSync(options.commandTriggerFile)) return
        clearInterval(triggerPoll)
        triggerPoll = null
        try {
          client.chat(options.startupCommand)
          writeJsonAtomic(path.join(options.outputDir, 'command-sent.json'), {
            schema_version: 1,
            sent_at: new Date().toISOString(),
            command: options.startupCommand
          })
          setTimeout(() => client.chat(options.stateCommand), 500)
          scenarioPoll = setInterval(() => client.chat(options.stateCommand), options.statePollSeconds * 1000)
        } catch (error) {
          reject(error)
          client.socket?.destroy()
        }
      }, 50)
    })

    client.once('error', error => reject(error))
    client.once('end', reason => {
      endReason = String(reason)
      clearTimeout(connectTimeout)
      clearScenarioTimers()
      if (measurementTimer) clearTimeout(measurementTimer)
      if (shutdownTimer) clearTimeout(shutdownTimer)
      if (!joined) reject(new Error(`Protocol connection ended before PLAY: ${endReason}`))
      else if (!expectedEnd) reject(new Error(`Protocol connection ended during measurement: ${endReason}`))
      else resolve()
    })
  })

  try {
    await completion
  } catch (error) {
    failure = error instanceof Error ? `${error.name}: ${error.message}` : String(error)
    client.socket?.destroy()
  }

  let snapshot
  try {
    snapshot = meter.snapshot()
    if (snapshot.unassignedInbound !== 0 || snapshot.unassignedOutboundDescriptors !== 0) {
      throw new Error(
        `Packet/frame attribution mismatch: inbound=${snapshot.unassignedInbound}, ` +
        `outbound=${snapshot.unassignedOutboundDescriptors}`
      )
    }
    if (snapshot.socketBytes.read !== snapshot.decoderBytes.read || snapshot.socketBytes.written !== snapshot.decoderBytes.written) {
      throw new Error(
        `Socket/decoder byte mismatch: socket=${JSON.stringify(snapshot.socketBytes)} ` +
        `decoder=${JSON.stringify(snapshot.decoderBytes)}`
      )
    }
  } catch (error) {
    failure = failure || (error instanceof Error ? `${error.name}: ${error.message}` : String(error))
    snapshot = {
      frames: meter.frames,
      aggregate: aggregateFrames(meter.frames),
      socketBytes: meter.socket
        ? {
            read: meter.socket.bytesRead - meter.baseline.socketRead,
            written: meter.socket.bytesWritten - meter.baseline.socketWritten
          }
        : { read: 0, written: 0 },
      decoderBytes: {
        read: meter.inbound.totalBytes - meter.baseline.read,
        written: meter.outbound.totalBytes - meter.baseline.written
      },
      unassignedInbound: meter.unassignedInbound.length,
      unassignedOutboundDescriptors: meter.outboundDescriptors.length
    }
  }

  const packetLog = snapshot.frames.map(frame => JSON.stringify(frame)).join('\n')
  fs.writeFileSync(path.join(options.outputDir, 'packets.jsonl'), packetLog ? `${packetLog}\n` : '', 'utf8')
  const summary = {
    schema_version: 1,
    status: failure ? 'failed' : 'complete',
    failure,
    started_at: startedAt,
    joined_at: joinedAt,
    scenario_ready_at: scenarioReadyAt,
    scenario_ready_text: scenarioReadyText,
    startup_command: options.startupCommand,
    scenario_ready_pattern: options.scenarioReadyPattern,
    measurement_started_at: measurementStartedAt,
    measurement_ended_at: measurementEndedAt,
    configured_warmup_seconds: options.warmupSeconds,
    configured_duration_seconds: options.durationSeconds,
    joined,
    end_reason: endReason,
    endpoint: { host: options.host, port: options.port },
    username: options.username,
    minecraft_version: options.version,
    implementation: {
      runtime: process.version,
      minecraft_protocol: packageMetadata.version
    },
    socket_payload_bytes: snapshot.socketBytes,
    minecraft_framed_protocol: {
      packets: {
        clientbound: snapshot.frames.filter(frame => frame.direction === 'clientbound').length,
        serverbound: snapshot.frames.filter(frame => frame.direction === 'serverbound').length
      },
      bytes: snapshot.decoderBytes,
      unassigned_clientbound_frames: snapshot.unassignedInbound,
      unassigned_serverbound_descriptors: snapshot.unassignedOutboundDescriptors,
      by_direction_state_name: snapshot.aggregate
    }
  }
  writeJsonAtomic(path.join(options.outputDir, 'summary.json'), summary)
  process.stdout.write(`${JSON.stringify({ event: 'complete', status: summary.status, output: options.outputDir })}\n`)
  if (failure) process.exitCode = 1
}

run().catch(error => {
  process.stderr.write(`${error.stack || error}\n`)
  process.exitCode = 1
})
