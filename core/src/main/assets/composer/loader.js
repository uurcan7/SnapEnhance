const deviceBridge = require('composer_core/src/DeviceBridge');

if (LOADER_CONFIG.logPrefix) {
    function internalLog(logLevel, args) {
        deviceBridge.copyToClipBoard(LOADER_CONFIG.logPrefix + "|" + logLevel + "|" + Array.from(args).join(" "));
    }

    console.log = function() {
        internalLog("info", arguments);
    }

    console.error = function() {
        internalLog("error", arguments);
    }

    console.warn = function() {
        internalLog("warn", arguments);
    }

    console.info = function() {
        internalLog("info", arguments);
    }

    console.debug = function() {
        internalLog("debug", arguments);
    }

    console.stacktrace = function() {
        return new Error().stack;
    }
}

if (LOADER_CONFIG.bypassCameraRollLimit) {
    ((module) => {
        module.MultiSelectClickHandler = new Proxy(module.MultiSelectClickHandler, {
            construct: function(target, args, newTarget) {
                args[1].selectionLimit = 9999999;
                return Reflect.construct(target, args, newTarget);
            },
        });
    })(require('memories_ui/src/clickhandlers/MultiSelectClickHandler'))
}

console.info("loader.js loaded");