import SwiftUI

struct StatusBubbleView: View {
    let text: String
    let style: BubbleStyle

    var body: some View {
        VStack(spacing: 4) {
            Text(text)
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(Color.black.opacity(0.86))
                .multilineTextAlignment(.center)
                .lineLimit(style == .speech ? 3 : 4)
                .padding(.horizontal, 12)
                .padding(.top, style == .speech ? 12 : 8)
                .padding(.bottom, style == .speech ? 16 : 14)
                .frame(minHeight: style == .speech ? 58 : 64)
                .background(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(Color(red: 1.0, green: 0.97, blue: 0.94).opacity(0.5))
                        .overlay(
                            RoundedRectangle(cornerRadius: 24, style: .continuous)
                                .stroke(
                                    Color.black.opacity(0.5),
                                    style: StrokeStyle(
                                        lineWidth: 2,
                                        dash: style == .thought ? [5, 4] : []
                                    )
                                )
                        )
                )

            if style == .thought {
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color(red: 1.0, green: 0.97, blue: 0.94).opacity(0.5))
                        .frame(width: 8, height: 8)
                        .overlay(
                            Circle()
                                .stroke(
                                    Color.black.opacity(0.5),
                                    style: StrokeStyle(lineWidth: 2, dash: [4, 3])
                                )
                        )
                    Circle()
                        .fill(Color(red: 1.0, green: 0.97, blue: 0.94).opacity(0.5))
                        .frame(width: 14, height: 14)
                        .overlay(
                            Circle()
                                .stroke(
                                    Color.black.opacity(0.5),
                                    style: StrokeStyle(lineWidth: 2, dash: [4, 3])
                                )
                        )
                }
                .offset(x: -18, y: -6)
            } else {
                SpeechTail()
                    .fill(Color(red: 1.0, green: 0.97, blue: 0.94).opacity(0.5))
                    .frame(width: 20, height: 14)
                    .overlay(
                        SpeechTail()
                            .stroke(Color.black.opacity(0.5), lineWidth: 2)
                    )
                    .offset(x: -22, y: -10)
            }
        }
        .frame(maxWidth: 172)
    }
}

struct SpeechTail: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + 2, y: rect.minY + 2))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - 1, y: rect.midY),
            control: CGPoint(x: rect.midX - 2, y: rect.minY - 2)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + 4, y: rect.maxY - 2),
            control: CGPoint(x: rect.midX, y: rect.maxY + 2)
        )
        path.closeSubpath()
        return path
    }
}
