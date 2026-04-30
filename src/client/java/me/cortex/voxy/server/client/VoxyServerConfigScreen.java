package me.cortex.voxy.server.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if HAS_DEBUG_SCREEN && !HAS_RENDER_PIPELINES {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
*///?}

/**
 * ModMenu config screen entry point. ModMenu calls
 * {@link VoxyServerModMenu#getModConfigScreenFactory()} which produces this
 * class wrapped around the parent screen.
 *
 * On MC 1.21.11 this screen delegates to {@link VoxyServerConfigScreenContent}
 * for a full multi-section editor (Server / Client / Graphs). On MC 1.21.1
 * and 26.1.2 only a Done button is shown -- the widget toolkit on those
 * versions diverges in ways (CycleButton.builder signature, GuiGraphics
 * removal) that aren't worth supporting until someone needs the GUI there.
 *
 * The wire protocol ({@link me.cortex.voxy.server.network.ConfigSnapshotPayload},
 * {@link me.cortex.voxy.server.network.ConfigEditPayload},
 * {@link me.cortex.voxy.server.network.ConfigEditResultPayload}) is fully
 * wired on the server, so the older-version stub can be replaced later
 * without any server-side changes.
 */
public class VoxyServerConfigScreen extends Screen {
	private final Screen parent;

	public VoxyServerConfigScreen(Screen parent) {
		super(Component.literal("Voxy Server"));
		this.parent = parent;
	}

	//? if HAS_DEBUG_SCREEN && !HAS_RENDER_PIPELINES {
	/*private VoxyServerConfigScreenContent content;

	// Public forwarder so the (same-package) content helper can register
	// widgets without relying on JLS protected-access nuances.
	public <T extends AbstractWidget> T addContentWidget(T w) {
		return this.addRenderableWidget(w);
	}

	@Override
	protected void init() {
		this.content = new VoxyServerConfigScreenContent(this, this::onClose);
		this.content.init();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		if (this.content != null) this.content.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.content != null && this.content.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		if (this.content != null) this.content.onClose();
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) mc.setScreen(parent);
	}
	*///?} else {
	@Override
	protected void init() {
		Button done = Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(this.width / 2 - 75, this.height - 30, 150, 20)
			.build();
		this.addRenderableWidget(done);
	}

	@Override
	public void onClose() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) mc.setScreen(parent);
	}
	//?}
}
