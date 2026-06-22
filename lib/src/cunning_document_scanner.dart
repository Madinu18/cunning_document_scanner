import 'dart:async';

import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import 'exceptions.dart';
import 'ios_scanner_options.dart';

/// A class that provides a simple way to scan documents.
class CunningDocumentScanner {
  /// The method channel used to interact with the native platform.
  static const MethodChannel _channel =
      MethodChannel('cunning_document_scanner');

  /// Starts the document scanning process.
  ///
  /// This method will open the camera and allow the user to scan documents.
  ///
  /// [noOfPages] is the maximum number of pages that can be scanned.
  /// [isGalleryImportAllowed] shows a "pick from gallery" button in the Android
  /// in-app camera so the user can choose an existing image instead of taking a
  /// photo. Defaults to false (button hidden).
  /// [isFlashControlAllowed] shows a flash on/off toggle button in the Android
  /// in-app camera. When false (default) the button is hidden and the torch
  /// stays forced on for scanning, as before.
  /// [iosScannerOptions] is a set of options for the iOS scanner.
  ///
  /// [guideAspect] / [guideInset] configure the Android in-app camera's framing
  /// guide rectangle (width:height aspect, and margin as a fraction of the
  /// shorter side). The captured crop is preset to this rectangle and can be
  /// corrected. These are ignored on iOS.
  ///
  /// Returns a list of paths to the scanned images, or null if the user cancels the operation.
  static Future<List<String>?> getPictures({
    int noOfPages = 100,
    bool isGalleryImportAllowed = false,
    bool isFlashControlAllowed = false,
    IosScannerOptions? iosScannerOptions,
    double? guideAspect,
    double? guideInset,
  }) async {
    Map<Permission, PermissionStatus> statuses = await [
      Permission.camera,
    ].request();
    if (statuses.containsValue(PermissionStatus.denied) ||
        statuses.containsValue(PermissionStatus.permanentlyDenied)) {
      throw const CunningDocumentScannerException.permissionDenied(
          'Camera permission not granted');
    }

    final List<dynamic>? pictures = await _channel.invokeMethod('getPictures', {
      'noOfPages': noOfPages,
      'isGalleryImportAllowed': isGalleryImportAllowed,
      'isFlashControlAllowed': isFlashControlAllowed,
      if (guideAspect != null) 'guideAspect': guideAspect,
      if (guideInset != null) 'guideInset': guideInset,
      if (iosScannerOptions != null)
        'iosScannerOptions': {
          'imageFormat': iosScannerOptions.imageFormat.name,
          'jpgCompressionQuality': iosScannerOptions.jpgCompressionQuality,
        }
    });
    return pictures?.map((e) => e as String).toList();
  }
}
