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
  (ShareScope, ShareTiming, bool)? invited;
  (String, ShareScope, ShareTiming, bool)? updated;
  String? revoked;
  List<String> uploads = [];
  List<Share> shares = [];
  List<FeedRow> feed = [];
  int feedCalls = 0;
  List<MonthRow> months = [];
  List<DayRow> days = [];
  bool linked = false;
  bool paused = false;
  bool? pausedSet;
  List<PatternRow> patterns = [];
  List<DriveEvent> driveEvents = [];
  List<WeeklyChange> weekly = [];
  List<WeeklyHabit> habits = [];
  int driveEventsCalls = 0, weeklyCalls = 0, habitsCalls = 0;
  final options = <String, ShareOptions>{};
  final optionSaves = <(String, ShareOptions)>[];
  List<FamilyLink> links = [];
  List<SentNote> notes = [];
  final views = <String, FamilyView>{};
  String? previewFamilyId;
  NoteError? noteError;
  (String, String)? sentNote;
  InvitePreview invitation = const InvitePreview(
    driverName: '아버지',
    scope: ShareScope.trend,
    timing: ShareTiming.d7,
    sharePattern: false,
  );
  int patternsCalls = 0, linksCalls = 0;
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
  Future<String> createInvite(
    ShareScope scope,
    ShareTiming timing,
    bool sharePattern,
  ) async {
    check();
    invited = (scope, timing, sharePattern);
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
    bool sharePattern,
  ) async {
    check();
    updated = (familyId, scope, timing, sharePattern);
    shares = [
      for (final share in shares)
        if (share.familyId == familyId)
          Share(
            familyId: familyId,
            familyName: share.familyName,
            scope: scope,
            timing: timing,
            sharePattern: sharePattern,
          )
        else
          share,
    ];
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
    return invitation;
  }

  @override
  Future<void> acceptInvite(String code) async {
    check();
    accepted = code;
  }

  @override
  Future<List<FeedRow>> familyFeed() async {
    check();
    feedCalls++;
    return List.of(feed);
  }

  @override
  Future<SharingState> sharingState() async {
    check();
    return SharingState(
      paused: paused,
      recipients: [
        for (final share in shares)
          SharingRecipient(
            familyName: share.familyName,
            scope: share.scope,
            timing: share.timing,
            sharePattern: share.sharePattern,
            options: options[share.familyId] ?? const ShareOptions(),
          ),
      ],
    );
  }

  @override
  Future<void> setSharingPaused(bool value) async {
    check();
    paused = value;
    pausedSet = value;
  }

  @override
  Future<FamilyView> previewFamilyView(String familyId) async {
    check();
    previewFamilyId = familyId;
    return views[familyId] ??
        FamilyView(
          paused: paused,
          feed: [],
          patterns: [],
          options: options[familyId] ?? const ShareOptions(),
        );
  }

  @override
  Future<void> setShareOptions(String familyId, ShareOptions choices) async {
    check();
    optionSaves.add((familyId, choices));
    options[familyId] = choices;
  }

  @override
  Future<List<DriveEvent>> familyDriveEvents() async {
    check();
    driveEventsCalls++;
    return List.of(driveEvents);
  }

  @override
  Future<List<WeeklyChange>> familyWeekly() async {
    check();
    weeklyCalls++;
    return List.of(weekly);
  }

  @override
  Future<List<WeeklyHabit>> familyHabits() async {
    check();
    habitsCalls++;
    return List.of(habits);
  }

  @override
  Future<List<PatternRow>> familyPatterns() async {
    check();
    patternsCalls++;
    return List.of(patterns);
  }

  @override
  Future<List<FamilyLink>> familyLinks() async {
    check();
    linksCalls++;
    return List.of(links);
  }

  @override
  Future<int> sendNote(String driverId, String body) async {
    check();
    if (noteError != null) throw noteError!;
    final text = body.trim();
    if (text.isEmpty || text.runes.length > 120) throw NoteError.invalid;
    final matches = links.where((l) => l.driverId == driverId);
    if (matches.isEmpty) throw NoteError.shareEnded;
    if (notes.where((n) => n.driverId == driverId && n.seenAt == null).length >=
        3) {
      throw NoteError.tooManyUnseen;
    }
    final id = notes.fold<int>(0, (m, n) => n.id > m ? n.id : m) + 1;
    sentNote = (driverId, text);
    notes.insert(
      0,
      SentNote(
        id: id,
        driverId: driverId,
        driverName: matches.first.driverName,
        body: text,
        createdAt: DateTime.now(),
        seenAt: null,
      ),
    );
    return id;
  }

  @override
  Future<List<SentNote>> sentNotes() async {
    check();
    return List.of(notes);
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
