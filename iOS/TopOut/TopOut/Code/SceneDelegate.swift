//rootViewController = HoldVC()
import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = HoldVC()
        self.window = window
        window.makeKeyAndVisible()
    }

    func reloadRootForLanguageChange() {
        guard let window else { return }
        UIView.transition(
            with: window,
            duration: 0.2,
            options: [.transitionCrossDissolve, .allowAnimatedContent]
        ) {
            window.rootViewController = HoldVC()
        }
    }
}
