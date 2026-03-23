import Foundation

enum ClawState: String {
    case idle
    case listening
    case thinking
    case searching
    case speaking
    case error

    var statusLabel: String {
        switch self {
        case .idle:
            return "Ready"
        case .listening:
            return "Listening"
        case .thinking:
            return "Thinking"
        case .searching:
            return "Searching"
        case .speaking:
            return "Speaking"
        case .error:
            return "Problem"
        }
    }
}

enum ClawMood: String {
    case calm
    case cheerful
    case excited
    case playful
    case serious
    case neutral
}

enum BubbleStyle {
    case thought
    case speech
}

enum ConversationRole {
    case user
    case claw
    case system
}

struct ConversationItem: Identifiable {
    let id = UUID()
    let role: ConversationRole
    let text: String
    let timestamp: Date = .now
}

enum WatchQuickPrompt: String, CaseIterable, Identifiable {
    case family = "Family"
    case vitals = "Vitals"
    case room = "Room"

    var id: String { rawValue }

    var prompt: String {
        switch self {
        case .family:
            return "How is the family doing in my Ant Farm room? Answer briefly and naturally."
        case .vitals:
            return "What sort of health and body help could ClawWatch provide on Apple Watch?"
        case .room:
            return "Write one short friendly update I could post to my room."
        }
    }
}
