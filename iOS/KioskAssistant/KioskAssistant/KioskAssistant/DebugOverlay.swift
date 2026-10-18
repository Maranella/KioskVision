import SwiftUI
import CoreGraphics
import KioskVisionCore

/// Sighted-debug overlay for the camera preview — draws each
/// `ActionableControl` (yellow), the tracked kiosk-screen
/// quadrilateral (cyan, dashed when unstable), and the hand
/// index-tip (red). Hidden from VoiceOver. Rendered only when
/// `ContentView` has the debug panel enabled.
struct DebugOverlay: View {
    let controls: [ActionableControl]
    let hand: HandPose
    let trackedScreen: TrackedScreen?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // OCR / control boxes
                ForEach(controls, id: \.id) { ctrl in
                    let rect = CGRect(
                        x: ctrl.normalizedBBox.minX * geo.size.width,
                        y: ctrl.normalizedBBox.minY * geo.size.height,
                        width: ctrl.normalizedBBox.width * geo.size.width,
                        height: ctrl.normalizedBBox.height * geo.size.height)
                    Rectangle()
                        .stroke(.yellow, lineWidth: 1.5)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                }
                // Tracked kiosk-screen quad
                if let screen = trackedScreen {
                    screenPath(screen.quad, size: geo.size)
                        .stroke(
                            Color.cyan,
                            style: StrokeStyle(
                                lineWidth: 2,
                                dash: screen.stable ? [] : [6, 4]))
                }
                // Hand index-tip
                if let tip = hand.indexTipNormalized,
                   hand.confidence >= Double(HandPose.minUsableConfidence)
                {
                    Circle()
                        .fill(.red)
                        .frame(width: 14, height: 14)
                        .position(
                            x: tip.x * geo.size.width,
                            y: tip.y * geo.size.height)
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func screenPath(
        _ quad: Quad, size: CGSize
    ) -> Path {
        Path { path in
            let pts = quad.corners.map { pt in
                CGPoint(
                    x: CGFloat(pt.x) * size.width,
                    y: CGFloat(pt.y) * size.height)
            }
            path.move(to: pts[0])
            for p in pts.dropFirst() { path.addLine(to: p) }
            path.closeSubpath()
        }
    }
}
