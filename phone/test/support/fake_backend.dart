import 'dart:async';

import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';

class FakeBackend implements Backend {
  FakeBackend({this.role, this.userId, this.displayName = '내 이름'});
  @override
  Role? role;
  @override
  String? userId;
  @override
  String? displayName;
  final events = StreamController<void>.broadcast(sync: true);
  @override
  Stream<void> get changes => events.stream;
  SignUpResult signupResult = SignUpResult.signedIn;
  Object? error;
  String? claimed;
  String? accepted;
  String? previewed;
  (ShareScope, ShareTiming)? invited;
  (String, ShareScope, ShareTiming)? updated;
  String? revoked;
  List<String> uploads = [];
  List<Share> shares = [];
  List<FeedRow> feed = [];
  List<MonthRow> months = [];
  List<DayRow> days = [];
  bool linked = false;
  final tokens = <(String, String)>[];
  void check() {
    if (error != null) throw error!;
  }

  @override
  Future<SignUpResult> signUp({
    required String email,
    required String password,
    required Role role,
    required String displayName,
  }) async {
    check();
    if (signupResult == SignUpResult.signedIn) {
      this.role = role;
      userId = 'u';
      this.displayName = displayName;
      events.add(null);
    }
    return signupResult;
  }

  @override
  Future<void> signIn(String email, String password) async {
    check();
    role ??= Role.driver;
    userId = 'u';
    events.add(null);
  }

  @override
  Future<void> signOut() async {
    check();
    role = null;
    userId = null;
    events.add(null);
  }

  @override
  Future<void> claimPairing(String code) async {
    check();
    claimed = code;
    linked = true;
  }

  @override
  Future<bool> carLinked() async {
    check();
    return linked;
  }

  @override
  Future<void> uploadImport(String contractAJson) async {
    check();
    uploads.add(contractAJson);
  }

  @override
  Future<String> createInvite(ShareScope scope, ShareTiming timing) async {
    check();
    invited = (scope, timing);
    return 'K7Q2M9XD';
  }

  @override
  Future<List<Share>> myShares() async {
    check();
    return List.of(shares);
  }

  @override
  Future<void> updateShare(
    String familyId,
    ShareScope scope,
    ShareTiming timing,
  ) async {
    check();
    updated = (familyId, scope, timing);
  }

  @override
  Future<void> revokeShare(String familyId) async {
    check();
    revoked = familyId;
    shares.removeWhere((s) => s.familyId == familyId);
  }

  @override
  Future<InvitePreview> previewInvite(String code) async {
    check();
    previewed = code;
    return const InvitePreview(
      driverName: '아버지',
      scope: ShareScope.trend,
      timing: ShareTiming.d7,
    );
  }

  @override
  Future<void> acceptInvite(String code) async {
    check();
    accepted = code;
  }

  @override
  Future<List<FeedRow>> familyFeed() async {
    check();
    return List.of(feed);
  }

  @override
  Future<List<MonthRow>> mySummaries() async {
    check();
    return List.of(months);
  }

  @override
  Future<List<DayRow>> myConditions() async {
    check();
    return List.of(days);
  }

  @override
  Future<void> registerPushToken(String token, String platform) async {
    check();
    tokens.add((token, platform));
  }
}
