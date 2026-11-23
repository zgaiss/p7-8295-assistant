package update

// UpdateClass describes rollout boundaries, not permission to activate.
type UpdateClass string

const (
	DataGeneration    UpdateClass = "data-generation"
	ControlledProcess UpdateClass = "controlled-process"
	BootstrapOnly     UpdateClass = "bootstrap-only"
)

type ComponentPolicy struct {
	ID, Filename, Target, Compatibility string
	Class                               UpdateClass
	Privileged                          bool
	// Always false in this draft until real receiver integration is verified.
	ProductionActivation bool
}

var policies = map[string]ComponentPolicy{
	"instrument":    {ID: "instrument", Filename: "p7assist", Target: "cluster-linux-armv7", Compatibility: "bundle protocol + framebuffer/bus health", Class: ControlledProcess, Privileged: true},
	"player":        {ID: "player", Filename: "p7videoplay", Target: "cluster-linux-armv7", Compatibility: "instrument/player IPC + animation pack format", Class: ControlledProcess, Privileged: true},
	"charge-pack":   {ID: "charge-pack", Filename: "charge.pack", Target: "cluster-linux-armv7", Compatibility: "player pack version/dimensions/frame limits", Class: DataGeneration},
	"charge-bin":    {ID: "charge-bin", Filename: "charge.bin", Target: "cluster-linux-armv7", Compatibility: "legacy renderer raw-frame format/dimensions", Class: DataGeneration},
	"font":          {ID: "font", Filename: "Alibaba-PuHuiTi-Regular.ttf", Target: "cluster-linux-armv7", Compatibility: "font parser + glyph/size limits; embedded fallback unchanged", Class: DataGeneration},
	"probe-bsd":     {ID: "probe-bsd", Filename: "p7bsd.jar", Target: "android-shell", Compatibility: "Android API + probe output protocol; stop old poller first", Class: ControlledProcess, Privileged: true},
	"probe-voltage": {ID: "probe-voltage", Filename: "p7volt.jar", Target: "android-shell", Compatibility: "Android API + probe output protocol; stop old poller first", Class: ControlledProcess, Privileged: true},
	"apk":           {ID: "apk", Filename: "app.apk", Target: "android-installer", Compatibility: "installed certificate/package/versionCode", Class: BootstrapOnly, Privileged: true},
	"boot":          {ID: "boot", Filename: "p7boot.sh", Target: "recovery-root", Compatibility: "explicit bootstrap only", Class: BootstrapOnly, Privileged: true},
	"watchdog":      {ID: "watchdog", Filename: "p7watch.sh", Target: "recovery-root", Compatibility: "explicit bootstrap only", Class: BootstrapOnly, Privileged: true},
	"control":       {ID: "control", Filename: "p7assistctl", Target: "recovery-root", Compatibility: "explicit bootstrap only", Class: BootstrapOnly, Privileged: true},
}

// Component returns a detached policy. Manifest data can never add a destination,
// executable path, script or component ID to this registry.
func Component(id string) (ComponentPolicy, bool) { p, ok := policies[id]; return p, ok }
func Components() []ComponentPolicy {
	ids := []string{"instrument", "player", "charge-pack", "charge-bin", "font", "probe-bsd", "probe-voltage", "apk", "boot", "watchdog", "control"}
	out := make([]ComponentPolicy, 0, len(ids))
	for _, id := range ids {
		out = append(out, policies[id])
	}
	return out
}
