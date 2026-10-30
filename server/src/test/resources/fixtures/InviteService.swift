import Foundation

// Bring-a-friend invites — the limit comes from remote config
protocol InviteLimiting {
    func friendLimit() -> Int
}

struct Invite {
    let email: String
    let sentAt: Date

    func isExpired(now: Date) -> Bool {
        return now.timeIntervalSince(sentAt) > 86_400
    }
}

final class InviteService: InviteLimiting {
    private let config: RemoteConfig
    private let defaultLimit = 10

    init(config: RemoteConfig) {
        self.config = config
    }

    func friendLimit() -> Int {
        let value = RemoteConfig.shared.int(forKey: "friend_limit")
        return value > 0 ? value : defaultLimit
    }

    func canInvite(_ invites: [Invite]) -> Bool {
        let active = invites.filter { !$0.isExpired(now: Date()) }
        return active.count < friendLimit()
    }
}

enum InviteState {
    case pending, accepted
}

extension InviteService {
    func reset() {
        log("reset")
    }
}
