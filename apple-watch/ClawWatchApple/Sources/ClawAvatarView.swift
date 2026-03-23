import SwiftUI

struct ClawAvatarView: View {
    let state: ClawState
    let mood: ClawMood

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 24.0)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            let motion = avatarMotion(time: t)

            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [
                                Color.orange.opacity(0.9),
                                Color(red: 0.63, green: 0.22, blue: 0.03)
                            ],
                            center: .center,
                            startRadius: 10,
                            endRadius: 70
                        )
                    )
                    .overlay(
                        Circle()
                            .stroke(Color.white.opacity(0.18), lineWidth: 1.5)
                    )
                    .shadow(color: glowColor.opacity(0.45), radius: 12)

                Text("🦞")
                    .font(.system(size: 54))
                    .scaleEffect(x: motion.flipX, y: 1.0)
                    .offset(x: motion.clawOffset, y: motion.verticalNudge)
            }
            .frame(width: 102, height: 102)
            .rotationEffect(.degrees(motion.rotation))
            .scaleEffect(motion.scale)
            .offset(x: motion.x, y: motion.y)
        }
    }

    private var glowColor: Color {
        switch mood {
        case .excited:
            return .yellow
        case .cheerful:
            return .mint
        case .playful:
            return .pink
        case .serious:
            return .red
        case .calm:
            return .cyan
        case .neutral:
            return .orange
        }
    }

    private func avatarMotion(time t: TimeInterval) -> (x: CGFloat, y: CGFloat, rotation: Double, scale: CGFloat, clawOffset: CGFloat, verticalNudge: CGFloat, flipX: CGFloat) {
        switch state {
        case .idle:
            return (
                x: CGFloat(sin(t * 1.3) * 1.2),
                y: CGFloat(cos(t * 1.0) * 1.5),
                rotation: sin(t * 0.9) * 2.0,
                scale: 1.0 + CGFloat(sin(t * 1.1) * 0.02),
                clawOffset: CGFloat(sin(t * 1.5) * 1.0),
                verticalNudge: CGFloat(cos(t * 1.2) * 0.6),
                flipX: 1.0
            )
        case .listening:
            return (
                x: CGFloat(sin(t * 2.4) * 3.8),
                y: CGFloat(cos(t * 1.7) * 1.8),
                rotation: sin(t * 2.1) * 5.5,
                scale: 1.03 + CGFloat(sin(t * 2.0) * 0.03),
                clawOffset: CGFloat(sin(t * 3.0) * 3.5),
                verticalNudge: CGFloat(cos(t * 2.2) * 1.4),
                flipX: sin(t * 3.1) > 0 ? 1.0 : 0.94
            )
        case .thinking, .searching:
            return (
                x: CGFloat(sin(t * 0.9) * 1.8),
                y: CGFloat(cos(t * 1.1) * 4.0),
                rotation: sin(t * 0.8) * 3.0,
                scale: 1.01 + CGFloat(cos(t * 1.0) * 0.025),
                clawOffset: CGFloat(sin(t * 1.8) * 2.0),
                verticalNudge: CGFloat(cos(t * 1.1) * 0.8),
                flipX: 1.0
            )
        case .speaking:
            switch mood {
            case .excited:
                return (
                    x: CGFloat(sin(t * 5.2) * 6.5),
                    y: CGFloat(abs(sin(t * 4.8)) * -7.5),
                    rotation: sin(t * 5.0) * 10.0,
                    scale: 1.06 + CGFloat(abs(sin(t * 4.4)) * 0.08),
                    clawOffset: CGFloat(sin(t * 7.5) * 7.0),
                    verticalNudge: CGFloat(cos(t * 6.0) * 2.0),
                    flipX: sin(t * 7.0) > 0 ? 1.0 : 0.88
                )
            case .playful:
                return (
                    x: CGFloat(sin(t * 3.8) * 7.5),
                    y: CGFloat(cos(t * 4.2) * 4.0),
                    rotation: sin(t * 4.1) * 12.0,
                    scale: 1.04 + CGFloat(sin(t * 4.7) * 0.05),
                    clawOffset: CGFloat(sin(t * 6.4) * 5.5),
                    verticalNudge: CGFloat(cos(t * 5.0) * 1.8),
                    flipX: sin(t * 6.0) > 0 ? 1.0 : 0.9
                )
            case .serious:
                return (
                    x: CGFloat(sin(t * 1.6) * 1.5),
                    y: CGFloat(abs(sin(t * 2.1)) * -4.5),
                    rotation: sin(t * 1.9) * 2.5,
                    scale: 1.01 + CGFloat(abs(sin(t * 2.0)) * 0.02),
                    clawOffset: CGFloat(sin(t * 2.8) * 1.8),
                    verticalNudge: CGFloat(cos(t * 2.0) * 0.8),
                    flipX: 1.0
                )
            case .calm:
                return (
                    x: CGFloat(sin(t * 1.2) * 1.8),
                    y: CGFloat(cos(t * 1.5) * 2.5),
                    rotation: sin(t * 1.3) * 2.0,
                    scale: 1.01 + CGFloat(sin(t * 1.4) * 0.02),
                    clawOffset: CGFloat(sin(t * 1.7) * 1.5),
                    verticalNudge: CGFloat(cos(t * 1.8) * 0.7),
                    flipX: 1.0
                )
            case .cheerful, .neutral:
                return (
                    x: CGFloat(sin(t * 3.0) * 4.5),
                    y: CGFloat(abs(sin(t * 3.4)) * -5.5),
                    rotation: sin(t * 3.1) * 7.5,
                    scale: 1.04 + CGFloat(abs(sin(t * 3.6)) * 0.05),
                    clawOffset: CGFloat(sin(t * 5.1) * 4.5),
                    verticalNudge: CGFloat(cos(t * 3.5) * 1.6),
                    flipX: sin(t * 5.4) > 0 ? 1.0 : 0.92
                )
            }
        case .error:
            return (
                x: CGFloat(sin(t * 6.0) * 2.6),
                y: CGFloat(cos(t * 5.5) * 1.1),
                rotation: sin(t * 6.2) * 7.0,
                scale: 0.98 + CGFloat(cos(t * 5.4) * 0.015),
                clawOffset: CGFloat(sin(t * 6.8) * 2.2),
                verticalNudge: 0.0,
                flipX: 1.0
            )
        }
    }
}
