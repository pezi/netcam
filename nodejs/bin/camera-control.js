#!/usr/bin/env node
/** Command-line interface for Camera Control automation. */

import { mkdir, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import readline from 'node:readline';

import {
  ApiError,
  CameraControlClient,
  ConnectionInterrupted,
  ProtocolError,
  TERMINAL_SESSION_STATES,
  decodePhoto,
} from '../src/index.js';

const RESOLUTIONS = ['low', 'medium', 'high', 'veryHigh', 'ultraHigh', 'max'];
const FLASH_MODES = ['off', 'auto', 'always', 'torch'];

const CONNECTION_OPTIONS = {
  ca: { type: 'string' },
  'password-env': { type: 'string', default: 'CAMERA_CONTROL_PASSWORD' },
  timeout: { type: 'string', default: '30' },
  'reconnect-attempts': { type: 'string', default: '5' },
  help: { type: 'boolean', short: 'h', default: false },
};

const CAPTURE_OPTIONS = {
  camera: { type: 'string', default: '1' },
  resolution: { type: 'string', default: 'high' },
  flash: { type: 'string', default: 'off' },
  'jpeg-quality': { type: 'string' },
};

const COMMANDS = {
  status: { ...CONNECTION_OPTIONS, compact: { type: 'boolean', default: false } },
  capture: {
    ...CONNECTION_OPTIONS,
    ...CAPTURE_OPTIONS,
    output: { type: 'string', default: 'photo.jpg' },
    'request-id': { type: 'string' },
  },
  'time-lapse': {
    ...CONNECTION_OPTIONS,
    ...CAPTURE_OPTIONS,
    interval: { type: 'string' },
    count: { type: 'string' },
    duration: { type: 'string' },
    poll: { type: 'string', default: '1' },
  },
};

const USAGE = `Control Camera Control through its verified-TLS WSS API.

usage: camera-control <command> <wss-url> [options]

commands:
  status       print camera, device, capture-session, and reliability status
  capture      capture and save one JPEG through the idempotent job API
  time-lapse   start, poll, and cancel a time-lapse capture session

connection options:
  --ca FILE                 verified Camera Control local CA certificate (required)
  --password-env NAME       environment variable holding the password
                            (default: CAMERA_CONTROL_PASSWORD)
  --timeout SECONDS         request timeout (default: 30)
  --reconnect-attempts N    idempotent retry attempts (default: 5)

capture options (capture, time-lapse):
  --camera N                camera index (default: 1)
  --resolution NAME         ${RESOLUTIONS.join(', ')} (default: high)
  --flash MODE              ${FLASH_MODES.join(', ')} (default: off)
  --jpeg-quality N          1-100

capture options:
  --output FILE             JPEG output path (default: photo.jpg)
  --request-id ID           stable idempotency key; generated when omitted

time-lapse options:
  --interval SECONDS        seconds between photos (required)
  --count N                 stop after this many photos
  --duration SECONDS        cancel after this many seconds
  --poll SECONDS            status poll interval (default: 1)
`;

async function main(argv) {
  const [command, ...rest] = argv;
  if (command === undefined || command === '--help' || command === '-h' || command === 'help') {
    process.stderr.write(USAGE);
    return command === undefined ? 2 : 0;
  }
  const options = COMMANDS[command];
  if (options === undefined) throw new Error(`unknown command '${command}'`);

  const positional = [];
  if (rest.length > 0 && !rest[0].startsWith('-')) positional.push(rest.shift());
  const { values } = parseArgs({ args: rest, options, allowPositionals: false });
  if (values.help) {
    process.stderr.write(USAGE);
    return 0;
  }

  const url = positional[0];
  if (url === undefined) throw new Error('the wss:// URL shown by the app is required');
  if (values.ca === undefined) throw new Error('--ca is required');

  const controls = buildControls(values);
  if (command !== 'status') {
    requireChoice('--resolution', values.resolution, RESOLUTIONS);
    requireChoice('--flash', values.flash, FLASH_MODES);
  }
  const password = await readPassword(values['password-env']);

  const client = new CameraControlClient(url, password, values.ca, {
    timeout: number(values.timeout, '--timeout') * 1000,
    reconnectAttempts: integer(values['reconnect-attempts'], '--reconnect-attempts'),
  });
  await client.connect();
  try {
    if (command === 'status') return await runStatus(client, values);
    if (command === 'capture') return await runCapture(client, values, controls);
    return await runTimeLapse(client, values, controls);
  } finally {
    client.close();
  }
}

async function runStatus(client, values) {
  const status = await client.status();
  process.stdout.write(`${stableJson(status, values.compact)}\n`);
  return 0;
}

async function runCapture(client, values, controls) {
  const requestId = values['request-id'] ?? `node-cli-${randomUUID()}`;
  const job = await client.captureJob({
    camera: integer(values.camera, '--camera'),
    resolution: values.resolution,
    flash: values.flash,
    controls,
    requestId,
  });
  if (job.state !== 'completed') {
    throw new ProtocolError(`Capture ended as ${job.state}: ${job.error?.message ?? ''}`);
  }
  const photo = job.result?.photo;
  if (photo === undefined) throw new ProtocolError('Completed capture job has no photo');
  const jpeg = decodePhoto(photo);
  const output = values.output;
  const parent = dirname(output);
  if (parent !== '.') await mkdir(parent, { recursive: true });
  await writeFile(output, jpeg);
  process.stdout.write(`Saved ${jpeg.length} bytes to ${output} (requestId=${requestId})\n`);
  return 0;
}

async function runTimeLapse(client, values, controls) {
  const interval = number(values.interval, '--interval');
  if (!(interval >= 1 && interval <= 86400)) {
    throw new Error('--interval must be between 1 and 86400 seconds');
  }
  const count = values.count === undefined ? undefined : integer(values.count, '--count');
  if (count !== undefined && !(count >= 2 && count <= 1000)) {
    throw new Error('--count must be between 2 and 1000');
  }
  const duration = values.duration === undefined ? undefined : number(values.duration, '--duration');
  if (duration !== undefined && !(duration > 0)) throw new Error('--duration must be positive');
  const poll = number(values.poll, '--poll');
  if (!(poll > 0)) throw new Error('--poll must be positive');

  let session = await client.startTimeLapse({
    intervalSeconds: interval,
    count,
    camera: integer(values.camera, '--camera'),
    resolution: values.resolution,
    flash: values.flash,
    controls,
  });
  const sessionId = String(session.id);
  process.stdout.write(`Started ${sessionId}; press Ctrl+C to stop.\n`);

  let stopRequested = false;
  const onInterrupt = () => {
    process.stderr.write('\nCancellation requested…\n');
    stopRequested = true;
  };
  process.on('SIGINT', onInterrupt);
  const deadline = duration === undefined ? undefined : Date.now() + duration * 1000;
  let lastSignature;
  try {
    while (!TERMINAL_SESSION_STATES.includes(session.state)) {
      const signature = `${session.state}/${session.capturedCount}`;
      if (signature !== lastSignature) {
        process.stdout.write(
          `${session.state}: ${session.capturedCount}/${session.targetCount ?? '∞'}\n`,
        );
        lastSignature = signature;
      }
      if (stopRequested || (deadline !== undefined && Date.now() >= deadline)) {
        session = await client.cancelCaptureSession(sessionId);
        break;
      }
      await sleep(poll * 1000);
      if (stopRequested) {
        session = await client.cancelCaptureSession(sessionId);
        break;
      }
      session = await client.captureSessionStatus(sessionId);
    }
  } finally {
    process.off('SIGINT', onInterrupt);
  }

  process.stdout.write(`${stableJson(session, false)}\n`);
  return ['completed', 'cancelled'].includes(session.state) ? 0 : 1;
}

function buildControls(values) {
  const quality = values['jpeg-quality'];
  if (quality === undefined) return {};
  const parsed = integer(quality, '--jpeg-quality');
  if (parsed < 1 || parsed > 100) throw new Error('--jpeg-quality must be between 1 and 100');
  return { jpegQuality: parsed };
}

function requireChoice(name, value, allowed) {
  if (!allowed.includes(value)) throw new Error(`${name} must be one of ${allowed.join(', ')}`);
}

function number(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be a number`);
  return parsed;
}

function integer(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) throw new Error(`${name} must be an integer`);
  return parsed;
}

/** Serialize with sorted keys so that output diffs stay readable. */
function stableJson(value, compact) {
  const sortedKeys = new Set();
  const collect = (node) => {
    if (node !== null && typeof node === 'object' && !Array.isArray(node)) {
      for (const key of Object.keys(node).sort()) {
        sortedKeys.add(key);
        collect(node[key]);
      }
    } else if (Array.isArray(node)) {
      node.forEach(collect);
    }
  };
  collect(value);
  return JSON.stringify(value, [...sortedKeys], compact ? undefined : 2);
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * Prefer the environment variable so that the password never appears in
 * process arguments, and prompt without echo otherwise.
 */
async function readPassword(variable) {
  const fromEnvironment = process.env[variable];
  if (fromEnvironment !== undefined) return fromEnvironment;
  const rl = readline.createInterface({ input: process.stdin, output: process.stderr, terminal: true });
  const question = new Promise((resolve) => rl.question('Camera Control password: ', resolve));
  rl.output.write = () => {};
  const password = await question;
  rl.close();
  process.stderr.write('\n');
  return password;
}

try {
  process.exitCode = await main(process.argv.slice(2));
} catch (error) {
  const known =
    error instanceof ApiError ||
    error instanceof ConnectionInterrupted ||
    error instanceof ProtocolError ||
    error instanceof Error;
  process.stderr.write(`camera-control: ${known ? error.message : String(error)}\n`);
  process.exitCode = 1;
}
