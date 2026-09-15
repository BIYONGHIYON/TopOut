import AVFoundation
import Photos

/// Main-queue owner for one Photos lookup, shared by save verification and review playback.
/// Cancellation is silent; completion is delivered at most once for success, failure or timeout.
final class PhotoVideoRequest {
    private let manager = PHImageManager.default()
    private var requestID: PHImageRequestID?
    private var generation = UUID()
    private var deadline: DispatchWorkItem?
    private var retry: DispatchWorkItem?
    private var completion: ((Result<AVAsset, Error>) -> Void)?

    var isLoading: Bool { completion != nil }

    func load(
        localIdentifier: String,
        attempts: Int = 3,
        timeout: TimeInterval = 20,
        completion: @escaping (Result<AVAsset, Error>) -> Void
    ) {
        dispatchPrecondition(condition: .onQueue(.main))
        cancel()
        self.completion = completion
        let token = generation
        let deadline = DispatchWorkItem { [weak self] in
            guard let self, self.generation == token else { return }
            self.finish(.failure(Self.lookupError()))
        }
        self.deadline = deadline
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: deadline)
        attempt(localIdentifier: localIdentifier, remaining: max(1, attempts), token: token)
    }

    func cancel() {
        generation = UUID()
        completion = nil
        deadline?.cancel()
        deadline = nil
        retry?.cancel()
        retry = nil
        if let requestID { manager.cancelImageRequest(requestID) }
        requestID = nil
    }

    private func attempt(localIdentifier: String, remaining: Int, token: UUID) {
        guard generation == token, isLoading else { return }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = assets.firstObject, asset.mediaType == .video, asset.duration > 0 else {
            retryOrFinish(localIdentifier: localIdentifier, remaining: remaining, token: token)
            return
        }
        let options = PHVideoRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        requestID = manager.requestAVAsset(forVideo: asset, options: options) { [weak self] avAsset, _, _ in
            DispatchQueue.main.async {
                guard let self, self.generation == token, self.isLoading else { return }
                self.requestID = nil
                if let avAsset {
                    self.finish(.success(avAsset))
                } else {
                    self.retryOrFinish(localIdentifier: localIdentifier, remaining: remaining, token: token)
                }
            }
        }
    }

    private func retryOrFinish(localIdentifier: String, remaining: Int, token: UUID) {
        guard remaining > 1 else {
            finish(.failure(Self.lookupError()))
            return
        }
        let retry = DispatchWorkItem { [weak self] in
            self?.attempt(localIdentifier: localIdentifier, remaining: remaining - 1, token: token)
        }
        self.retry = retry
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: retry)
    }

    private func finish(_ result: Result<AVAsset, Error>) {
        let callback = completion
        cancel()
        callback?(result)
    }

    private static func lookupError() -> Error {
        NSError(domain: "TopOut.PhotoLibrary", code: 4,
                userInfo: [NSLocalizedDescriptionKey: L("error.open_saved_file")])
    }

    deinit { cancel() }
}
