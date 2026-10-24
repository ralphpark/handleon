import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'timeline_parser.dart';

const maxImportInputBytes = 25 * 1024 * 1024;
const maxImportUploadBytes = 5000000;

class ImportFile {
  const ImportFile(this.name, this.bytes);
  final String name;
  final List<int> bytes;
}

Future<ImportFile?> pickTimelineFile() async {
  final file = await FilePicker.pickFile(
    type: FileType.custom,
    allowedExtensions: ['json'],
  );
  if (file == null) return null;
  final length = file.lengthSync();
  if (length != null && length > maxImportInputBytes) {
    throw const FormatException();
  }
  final stream = file.readAsByteStream();
  final builder = BytesBuilder(copy: false);
  await for (final part in stream) {
    if (builder.length + part.length > maxImportInputBytes) {
      throw const FormatException();
    }
    builder.add(part);
  }
  return ImportFile(file.name, builder.takeBytes());
}

List<String> encodeImportChunks(List<PastTrip> trips) {
  final result = <String>[];
  void append(List<PastTrip> part) {
    final json = jsonEncode({
      'version': 1,
      'source': 'google_timeline',
      'trips': part.map((t) => t.toJson()).toList(),
    });
    if (utf8.encode(json).length <= maxImportUploadBytes) {
      result.add(json);
      return;
    }
    if (part.length <= 1) throw const FormatException('읽을 수 없는 파일이에요');
    final half = part.length ~/ 2;
    append(part.sublist(0, half));
    append(part.sublist(half));
  }

  for (final part in chunk(trips)) {
    append(part);
  }
  return result;
}

class ImportPreview {
  const ImportPreview(this.count, this.start, this.end, this.files);
  final int count;
  final DateTime? start, end;
  final List<String> files;
}

ImportPreview parseImportFile(List<int> bytes) {
  if (bytes.length > maxImportInputBytes) throw const FormatException();
  final trips = TimelineParser.parse(utf8.decode(bytes));
  trips.sort((a, b) => a.start.compareTo(b.start));
  return ImportPreview(
    trips.length,
    trips.isEmpty
        ? null
        : trips.first.start.toUtc().add(const Duration(hours: 9)),
    trips.isEmpty
        ? null
        : trips.last.start.toUtc().add(const Duration(hours: 9)),
    encodeImportChunks(trips),
  );
}

class ImportScreen extends StatefulWidget {
  const ImportScreen({
    super.key,
    required this.backend,
    this.pickFile = pickTimelineFile,
  });
  final Backend backend;
  final Future<ImportFile?> Function() pickFile;
  @override
  State<ImportScreen> createState() => _ImportScreenState();
}

class _ImportScreenState extends State<ImportScreen> {
  ImportPreview? preview;
  String? name, message;
  bool busy = false, sent = false;
  int uploaded = 0;
  Future<void> pick() async {
    setState(() {
      busy = true;
      message = null;
    });
    try {
      final file = await widget.pickFile();
      if (file != null) {
        if (mounted) {
          setState(() {
            preview = null;
            sent = false;
            uploaded = 0;
          });
        }
        final parsed = await compute(parseImportFile, file.bytes);
        if (mounted) {
          setState(() {
            preview = parsed;
            name = file.name;
          });
        }
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          message = '읽을 수 없는 파일이에요';
          preview = null;
        });
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> send() async {
    setState(() {
      busy = true;
      message = null;
    });
    try {
      final files = preview!.files;
      while (uploaded < files.length) {
        await widget.backend.uploadImport(files[uploaded]);
        uploaded++;
        if (!mounted) return;
        setState(() {});
      }
      if (mounted) setState(() => sent = true);
    } catch (e) {
      if (mounted) setState(() => message = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '지난 운전 기록 가져오기',
    backLabel: '내 운전 요약',
    subtitle: '핸들온을 쓰기 전의 운전도 기준에 넣을 수 있어요. 차로 이동한 기록만 골라 차에 보내요.',
    children: [
      Panel(
        child: Column(
          children: spaced([
            for (final (i, text) in [
              (1, '구글 지도에서 타임라인을 파일로 내보내요'),
              (2, '내보낸 파일을 아래에서 골라요'),
              (3, '차가 다음에 켜질 때 가져가요'),
            ])
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  CircleAvatar(
                    radius: 14,
                    backgroundColor: colors(context).brandSoft,
                    child: Text(
                      '$i',
                      style: bodyStyle(context, color: colors(context).brand),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(child: Text(text)),
                ],
              ),
          ], 14),
        ),
      ),
      Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: spaced([
            if (preview != null) ...[
              Text(
                name ?? '타임라인.json',
                style: bodyStyle(context, weight: FontWeight.w600),
              ),
              Muted(
                preview!.count == 0
                    ? '차로 이동한 기록이 없어요'
                    : '차로 이동한 기록 ${preview!.count}건 · ${preview!.start!.year}년 ${preview!.start!.month}월 ~ ${preview!.end!.year}년 ${preview!.end!.month}월',
              ),
            ],
            SecondaryButton(
              preview == null ? '파일 고르기' : '다른 파일 고르기',
              onPressed: busy ? null : pick,
            ),
          ], 12),
        ),
      ),
      const Notice('위치가 담긴 파일이에요. 차가 가져가면 서버에서 바로 지우고, 가족에게는 어떤 경우에도 보이지 않아요.'),
      if (message != null) Notice(message!),
      PrimaryButton(
        busy ? '잠시만 기다려 주세요' : '차로 보내기',
        onPressed: busy || sent || preview == null || preview!.count == 0
            ? null
            : send,
      ),
      if (sent) const Notice('차가 다음에 켜질 때 가져가요', success: true),
    ],
  );
}
