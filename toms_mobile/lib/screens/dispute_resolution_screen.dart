import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:lucide_icons/lucide_icons.dart';
import 'package:intl/intl.dart';
import '../theme/toms_theme.dart';
import '../services/database_service.dart';
import '../models/models.dart';

class DisputeResolutionScreen extends StatefulWidget {
  const DisputeResolutionScreen({super.key});

  @override
  State<DisputeResolutionScreen> createState() => _DisputeResolutionScreenState();
}

class _DisputeResolutionScreenState extends State<DisputeResolutionScreen> {
  final MobileScannerController _scannerController = MobileScannerController(
    detectionSpeed: DetectionSpeed.noDuplicates,
    facing: CameraFacing.back,
  );
  
  bool _isProcessing = false;
  String? _scannedData;
  PassengerLog? _verifiedLog;
  bool _searchPerformed = false;
  bool _isValidFormat = false;

  // Parsed QR components
  String _qrVehId = '';
  String _qrTimestamp = '';
  int _qrFare = 0;
  String _qrUid = '';

  final _dbService = DatabaseService();

  @override
  void dispose() {
    _scannerController.dispose();
    super.dispose();
  }

  void _handleQrDetect(BarcodeCapture capture) async {
    if (_isProcessing) return;
    
    final List<Barcode> barcodes = capture.barcodes;
    if (barcodes.isEmpty) return;
    
    final String? rawValue = barcodes.first.rawValue;
    if (rawValue == null || rawValue.isEmpty) return;

    setState(() {
      _isProcessing = true;
      _scannedData = rawValue;
      _searchPerformed = false;
      _verifiedLog = null;
    });

    // Parse format: TOMS,VEH_ID,TIMESTAMP,FARE,UID
    final parts = rawValue.split(',');
    if (parts.length == 5 && parts[0] == 'TOMS') {
      _isValidFormat = true;
      _qrVehId = parts[1];
      
      final tsVal = int.tryParse(parts[2]) ?? 0;
      if (tsVal > 0) {
        final dt = DateTime.fromMillisecondsSinceEpoch(tsVal * 1000);
        _qrTimestamp = DateFormat('yyyy-MM-dd HH:mm:ss').format(dt);
      } else {
        _qrTimestamp = 'N/A';
      }

      _qrFare = int.tryParse(parts[3]) ?? 0;
      _qrUid = parts[4].toUpperCase();

      // Query database
      final log = await _dbService.lookupDisputedLog(_qrUid, _qrFare);
      setState(() {
        _verifiedLog = log;
        _searchPerformed = true;
        _isProcessing = false;
      });
    } else {
      setState(() {
        _isValidFormat = false;
        _searchPerformed = true;
        _isProcessing = false;
      });
    }
  }

  void _resetScanner() {
    setState(() {
      _isProcessing = false;
      _scannedData = null;
      _verifiedLog = null;
      _searchPerformed = false;
      _isValidFormat = false;
    });
  }

  String _formatCurrency(int centavos) {
    return '₱${(centavos / 100).toStringAsFixed(2)}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TomsTheme.bgDark,
      appBar: AppBar(
        title: const Text('Ticket Dispute Resolution'),
        actions: [
          IconButton(
            icon: const Icon(LucideIcons.flashlight),
            onPressed: () => _scannerController.toggleTorch(),
          ),
          IconButton(
            icon: const Icon(LucideIcons.refreshCw),
            onPressed: () => _scannerController.switchCamera(),
          ),
        ],
      ),
      body: Column(
        children: [
          // Camera scanner container
          Expanded(
            flex: 4,
            child: Stack(
              children: [
                if (!_isProcessing && _scannedData == null)
                  MobileScanner(
                    controller: _scannerController,
                    onDetect: _handleQrDetect,
                  )
                else
                  Container(
                    color: Colors.black.withValues(alpha: 0.8),
                    child: const Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          CircularProgressIndicator(color: TomsTheme.accent),
                          SizedBox(height: 16),
                          Text(
                            'Processing ticket data...',
                            style: TextStyle(color: Colors.white70, fontSize: 14),
                          ),
                        ],
                      ),
                    ),
                  ),
                
                // Centered target scanning frame
                if (!_isProcessing && _scannedData == null)
                  Center(
                    child: Container(
                      width: 250,
                      height: 250,
                      decoration: BoxDecoration(
                        border: Border.all(color: TomsTheme.accent.withValues(alpha: 0.8), width: 3),
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: [
                          BoxShadow(
                            color: TomsTheme.accent.withValues(alpha: 0.2),
                            blurRadius: 15,
                            spreadRadius: 2,
                          ),
                        ],
                      ),
                    ),
                  ),
                
                // Overlay tip
                if (!_isProcessing && _scannedData == null)
                  Positioned(
                    bottom: 20,
                    left: 0,
                    right: 0,
                    child: Center(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.7),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: const Text(
                          'Point camera at the passenger ticket QR code',
                          style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          
          // Result panel
          Expanded(
            flex: 5,
            child: Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                color: TomsTheme.bgCard,
                borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
                border: Border(top: BorderSide(color: TomsTheme.border)),
              ),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'VERIFICATION STATUS',
                      style: Theme.of(context).textTheme.labelLarge?.copyWith(
                            color: TomsTheme.textSecondary,
                            letterSpacing: 1.2,
                          ),
                    ),
                    const SizedBox(height: 12),
                    
                    if (!_searchPerformed)
                      _buildScanAwaitingCard()
                    else if (!_isValidFormat)
                      _buildInvalidFormatCard()
                    else if (_verifiedLog != null)
                      _buildVerifiedCard()
                    else
                      _buildNotFoundCard(),
                      
                    const SizedBox(height: 24),
                    
                    if (_searchPerformed) ...[
                      // Scanned Ticket Metadata (Parsed details)
                      const Text(
                        'TICKET METADATA',
                        style: TextStyle(
                          color: TomsTheme.textSecondary,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 1.0,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: TomsTheme.bgCardLight,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: TomsTheme.border),
                        ),
                        child: Column(
                          children: [
                            _buildInfoRow('Vehicle ID', _qrVehId),
                            const Divider(color: TomsTheme.border, height: 20),
                            _buildInfoRow('UID (MAC)', _qrUid),
                            const Divider(color: TomsTheme.border, height: 20),
                            _buildInfoRow('Fare Charged', _formatCurrency(_qrFare)),
                            const Divider(color: TomsTheme.border, height: 20),
                            _buildInfoRow('Issued Timestamp', _qrTimestamp),
                          ],
                        ),
                      ),
                      const SizedBox(height: 24),
                      
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: _resetScanner,
                          icon: const Icon(LucideIcons.scan),
                          label: const Text('SCAN ANOTHER TICKET'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: TomsTheme.accent,
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildScanAwaitingCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: TomsTheme.bgCardLight,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: TomsTheme.border),
      ),
      child: Column(
        children: [
          Icon(
            LucideIcons.scanFace,
            size: 48,
            color: TomsTheme.textSecondary.withValues(alpha: 0.4),
          ),
          const SizedBox(height: 16),
          const Text(
            'Ready to Scan',
            style: TextStyle(
              color: TomsTheme.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            'Bring a passenger\'s QR ticket receipt in front of the camera to verify its status against the local database.',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: TomsTheme.textSecondary,
              fontSize: 12,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInvalidFormatCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: TomsTheme.danger.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: TomsTheme.danger.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(LucideIcons.alertTriangle, color: TomsTheme.danger, size: 24),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Invalid Code Format',
                  style: TextStyle(
                    color: TomsTheme.danger,
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'The scanned code does not follow the standard TOMS receipt format. Raw data:\n"$_scannedData"',
                  style: const TextStyle(
                    color: TomsTheme.textSecondary,
                    fontSize: 12,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNotFoundCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: TomsTheme.danger.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: TomsTheme.danger.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(LucideIcons.shieldAlert, color: TomsTheme.danger, size: 24),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Ticket Not Found (Potential Fraud)',
                  style: TextStyle(
                    color: TomsTheme.danger,
                    fontWeight: FontWeight.w700,
                    fontSize: 15,
                  ),
                ),
                const SizedBox(height: 6),
                const Text(
                  'No transaction matching this Slave UID was found in this vehicle\'s local database. This receipt may be forged, from another device, or from a different shift.',
                  style: TextStyle(
                    color: TomsTheme.textSecondary,
                    fontSize: 12,
                    height: 1.4,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildVerifiedCard() {
    final log = _verifiedLog!;
    final dt = DateTime.fromMillisecondsSinceEpoch(log.timestamp * 1000);
    final formattedTime = DateFormat('MMMM d, h:mm a').format(dt);
    
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: TomsTheme.success.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: TomsTheme.success.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(LucideIcons.checkCircle2, color: TomsTheme.success, size: 24),
              SizedBox(width: 10),
              Text(
                'VERIFIED TICKET',
                style: TextStyle(
                  color: TomsTheme.success,
                  fontWeight: FontWeight.w800,
                  fontSize: 16,
                  letterSpacing: 0.5,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          _buildInfoRow('Local Log ID', '#${log.id}'),
          const SizedBox(height: 8),
          _buildInfoRow('Boarded At', log.boardingStop.isNotEmpty ? log.boardingStop : 'Unknown Stop'),
          const SizedBox(height: 8),
          _buildInfoRow('Destination', log.destinationStop.isNotEmpty ? log.destinationStop : 'Unknown Stop'),
          const SizedBox(height: 8),
          _buildInfoRow('Fare Checked', _formatCurrency(log.fareCentavos)),
          const SizedBox(height: 8),
          _buildInfoRow('Verified On', formattedTime),
          const SizedBox(height: 12),
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: log.synced 
                      ? TomsTheme.success.withValues(alpha: 0.2) 
                      : TomsTheme.warning.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  log.synced ? 'CLOUD SYNCED' : 'CACHED OFFLINE',
                  style: TextStyle(
                    color: log.synced ? TomsTheme.success : TomsTheme.warning,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          )
        ],
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(color: TomsTheme.textSecondary, fontSize: 12),
        ),
        Text(
          value,
          style: const TextStyle(
            color: TomsTheme.textPrimary,
            fontWeight: FontWeight.w600,
            fontSize: 12,
          ),
        ),
      ],
    );
  }
}
