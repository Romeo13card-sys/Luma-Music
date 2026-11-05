import 'package:material_ui/material_ui.dart';

/// Shared hover+scale: MouseRegion + AnimatedScale in one place.
///
/// Replaces duplicates:
/// - media_card.dart `_isHovered/_isPressed + AnimatedScale`
/// - home_cover_widget.dart `MouseRegion + ValueListenable + AnimatedScale`
/// - player_bar.dart `_TrackInfo MouseRegion + AnimatedScale`
class HoverScale extends StatefulWidget {
  final Widget child;
  final double hoverScale;
  final Duration duration;
  final Curve curve;
  final MouseCursor cursor;
  final VoidCallback? onTap;
  final bool enabled;
  final ValueChanged<bool>? onHoverChanged;

  const HoverScale({
    required this.child,
    super.key,
    this.hoverScale = 1.05,
    this.duration = const Duration(milliseconds: 200),
    this.curve = Curves.easeOutBack,
    this.cursor = SystemMouseCursors.basic,
    this.onTap,
    this.enabled = true,
    this.onHoverChanged,
  });

  @override
  State<HoverScale> createState() => _HoverScaleState();
}

class _HoverScaleState extends State<HoverScale> {
  bool _hovered = false;

  @override
  Widget build(BuildContext context) {
    final content = AnimatedScale(
      scale: _hovered && widget.enabled ? widget.hoverScale : 1.0,
      duration: widget.duration,
      curve: widget.curve,
      child: widget.child,
    );
    return MouseRegion(
      cursor: widget.cursor,
      onEnter: (_) {
        setState(() => _hovered = true);
        widget.onHoverChanged?.call(true);
      },
      onExit: (_) {
        setState(() => _hovered = false);
        widget.onHoverChanged?.call(false);
      },
      child: widget.onTap != null
          ? GestureDetector(onTap: widget.onTap, child: content)
          : content,
    );
  }
}
