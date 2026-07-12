import 'package:speech_to_text/speech_to_text.dart';

/// Thin wrapper over `speech_to_text`, mirroring [AiParser]'s testable seam:
/// tests override [speechServiceProvider] with a fake that drives [listen]
/// without a real microphone.
///
// ponytail: SpeechToText.initialize() requests the mic permission itself on
// both platforms, so no permission_handler call is needed here.
class SpeechService {
  SpeechService([SpeechToText? stt]) : _stt = stt ?? SpeechToText();
  final SpeechToText _stt;
  bool _ready = false;

  Future<bool> init() async {
    _ready = _ready || await _stt.initialize();
    return _ready;
  }

  Future<void> listen({required void Function(String) onResult, String? localeId}) =>
      _stt.listen(
        onResult: (r) => onResult(r.recognizedWords),
        listenOptions: SpeechListenOptions(localeId: localeId),
      );

  Future<void> stop() => _stt.stop();

  bool get isListening => _stt.isListening;
}
