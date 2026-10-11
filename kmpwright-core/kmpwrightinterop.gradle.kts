package = kmpwright
cinterops {
    kmpwright {
        defFile = kmpwright.def
        compilerOpts = -framework WebKit -framework UIKit -F/System/Library/Frameworks
        linkerOpts = -framework WebKit -framework UIKit
    }
}
