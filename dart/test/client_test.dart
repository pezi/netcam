/// Offline protocol tests driven by a scripted in-memory channel.
library;

import 'dart:collection';
import 'dart:convert';

import 'package:camera_control_client/camera_control_client.dart';
import 'package:test/test.dart';

/// Replays scripted server messages and answers requests the way the app does.
final class FakeChannel implements Channel {
  FakeChannel(List<JsonObject> script) {
    _messages.addAll(script);
  }

  final Queue<JsonObject> _messages = Queue<JsonObject>();
  final List<JsonObject> sent = <JsonObject>[];
  bool closed = false;

  @override
  void send(String message) {
    final JsonObject payload = jsonDecode(message) as JsonObject;
    sent.add(payload);
    final Object? id = payload['id'];
    switch (payload['action']) {
      case 'authenticate':
        _messages.addFirst(<String, Object?>{
          'id': id,
          'ok': true,
          'result': <String, Object?>{'protocolVersion': apiProtocolVersion},
        });
      case 'status':
        _messages.addLast(<String, Object?>{
          'event': 'captureSession',
          'session': <String, Object?>{'id': 'session-1', 'state': 'waiting'},
        });
        _messages.addLast(<String, Object?>{
          'id': id,
          'ok': true,
          'result': <String, Object?>{'ready': true},
        });
    }
  }

  @override
  Future<String> receive(Duration timeout) async {
    if (_messages.isEmpty) {
      throw const ConnectionInterrupted('the connection was closed by the peer');
    }
    return jsonEncode(_messages.removeFirst());
  }

  @override
  Future<void> close() async {
    closed = true;
  }
}

CameraControlClient clientFor(FakeChannel channel) =>
    CameraControlClient.withChannelFactory(
      url: 'wss://192.168.1.50:8080/ws',
      password: 'secret123',
      options: const Options(timeout: Duration(seconds: 1)),
      channelFactory: (String url, Duration openTimeout) async => channel,
    );

JsonObject hello(int protocolVersion) =>
    <String, Object?>{'event': 'hello', 'protocolVersion': protocolVersion};

void main() {
  test('authenticates protocol v4 and queues events', () async {
    final FakeChannel channel = FakeChannel(<JsonObject>[hello(apiProtocolVersion)]);
    final CameraControlClient client = clientFor(channel);
    await client.connect();

    expect(await client.status(), <String, Object?>{'ready': true});
    final JsonObject event = await client.waitForEvent(
      'captureSession',
      timeout: const Duration(milliseconds: 100),
    );
    expect((event['session']! as JsonObject)['id'], 'session-1');
    expect(channel.sent.first['action'], 'authenticate');

    await client.close();
    expect(channel.closed, isTrue);
  });

  test('rejects API errors', () async {
    final FakeChannel channel = FakeChannel(<JsonObject>[
      hello(apiProtocolVersion),
      <String, Object?>{
        'id': 'dart-2',
        'ok': false,
        'error': <String, Object?>{'code': 'camera_busy', 'message': 'Busy'},
      },
    ]);
    final CameraControlClient client = clientFor(channel);
    await client.connect();

    expect(
      () => client.request(
        'cancelCaptureSession',
        parameters: <String, Object?>{'sessionId': 'session-1'},
        retryOnDisconnect: false,
      ),
      throwsA(isA<ApiException>().having((ApiException e) => e.code, 'code', 'camera_busy')),
    );
  });

  test('rejects old protocol servers', () async {
    final CameraControlClient client = clientFor(FakeChannel(<JsonObject>[hello(1)]));
    expect(
      client.connect(),
      throwsA(isA<ProtocolException>().having(
        (ProtocolException e) => e.message,
        'message',
        contains('Unsupported server greeting'),
      )),
    );
  });

  test('requires a wss URL', () {
    expect(
      () => CameraControlClient.withChannelFactory(
        url: 'ws://192.168.1.50:8080/ws',
        password: 'x',
        channelFactory: (String url, Duration timeout) async => FakeChannel(<JsonObject>[]),
      ),
      throwsA(isA<ConfigurationException>()),
    );
  });

  test('decodes only JPEG photos', () {
    expect(
      () => decodePhoto(<String, Object?>{'mimeType': 'image/png'}),
      throwsA(isA<ProtocolException>()),
    );
    final List<int> jpeg = decodePhoto(
      <String, Object?>{'mimeType': 'image/jpeg', 'dataBase64': '/9j/4AAQ'},
    );
    expect(jpeg.take(2), <int>[0xff, 0xd8]);
  });
}
