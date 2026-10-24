/// Serializes this device's registrations and removes them before sign-out.
/// A failed cleanup leaves the session active so the user can retry.
class DevicePushTokens {
  DevicePushTokens({
    required this.registerToken,
    required this.removeTokens,
    required this.endSession,
  });

  final Future<void> Function(String, String) registerToken;
  final Future<void> Function(Set<String>) removeTokens;
  final Future<void> Function() endSession;
  final _tokens = <String>{};
  Future<void> _pending = Future.value();
  Future<void>? _signingOut;

  Future<void> register(String token, String platform) {
    if (_signingOut != null) return Future.value();
    // Keep the token even on an ambiguous network failure: the server may
    // have committed the registration before the response was interrupted.
    _tokens.add(token);
    final result = _pending.then((_) => registerToken(token, platform));
    _pending = result.then<void>((_) {}, onError: (Object _) {});
    return result;
  }

  Future<void> signOut() {
    if (_signingOut != null) return _signingOut!;
    final result = _finishSignOut().whenComplete(() => _signingOut = null);
    _signingOut = result;
    return result;
  }

  Future<void> _finishSignOut() async {
    await _pending;
    if (_tokens.isNotEmpty) await removeTokens(Set.unmodifiable(_tokens));
    await endSession();
    _tokens.clear();
  }
}
