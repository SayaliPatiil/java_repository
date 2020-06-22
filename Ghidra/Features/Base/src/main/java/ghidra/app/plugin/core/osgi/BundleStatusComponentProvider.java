/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.osgi;

import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.*;
import javax.swing.event.TableModelEvent;

import docking.action.builder.ActionBuilder;
import docking.util.AnimationUtils;
import docking.widgets.filechooser.GhidraFileChooser;
import docking.widgets.filechooser.GhidraFileChooserMode;
import docking.widgets.table.*;
import generic.jar.ResourceFile;
import generic.util.Path;
import ghidra.app.services.ConsoleService;
import ghidra.docking.settings.Settings;
import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.preferences.Preferences;
import ghidra.util.HelpLocation;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.filechooser.GhidraFileChooserModel;
import ghidra.util.filechooser.GhidraFileFilter;
import ghidra.util.table.column.AbstractGColumnRenderer;
import ghidra.util.table.column.AbstractWrapperTypeColumnRenderer;
import ghidra.util.task.*;
import resources.Icons;
import resources.ResourceManager;

/**
 * component for managing OSGi bundle status
 */
public class BundleStatusComponentProvider extends ComponentProviderAdapter {

	static final String BUNDLE_GROUP = "0bundle group";
	static final String BUNDLE_LIST_GROUP = "1bundle list group";

	static final String PREFENCE_LAST_SELECTED_BUNDLE = "LastGhidraBundle";

	private static final Color DARK_GREEN = new Color(0.0f, .6f, 0.0f);
	private static final Color DARK_RED = new Color(0.6f, .1f, 0.0f);

	private JPanel panel;
	private LessFreneticGTable bundleStatusTable;
	private final BundleStatusTableModel bundleStatusTableModel;
	private GTableFilterPanel<BundleStatus> filterPanel;

	private GhidraFileChooser fileChooser;
	private GhidraFileFilter filter;
	private final BundleHost bundleHost;

	/**
	 * {@link BundleStatusComponentProvider} visualizes bundle status and exposes actions for
	 * adding, removing, enabling, disabling, activating, and deactivating bundles.
	 * 
	 * @param tool the tool
	 * @param owner the owner name
	 * @param bundleHost the bundle host
	 */
	public BundleStatusComponentProvider(PluginTool tool, String owner, BundleHost bundleHost) {
		super(tool, "BundleManager", owner);
		setHelpLocation(new HelpLocation("BundleManager", "BundleManager"));
		setTitle("Bundle Manager");

		this.bundleHost = bundleHost;
		this.bundleStatusTableModel = new BundleStatusTableModel(this, bundleHost);

		bundleStatusTableModel.addListener(new MyBundleStatusChangeRequestListener());

		this.filter = new GhidraFileFilter() {
			@Override
			public String getDescription() {
				return "Source code directory, bundle (*.jar), or bnd script (*.bnd)";
			}

			@Override
			public boolean accept(File file, GhidraFileChooserModel model) {
				return GhidraBundle.getType(file) != GhidraBundle.Type.INVALID;
			}
		};
		this.fileChooser = null;

		build();
		addToTool();
		createActions();
	}

	private void build() {
		panel = new JPanel(new BorderLayout(5, 5));

		bundleStatusTable = new LessFreneticGTable(bundleStatusTableModel);
		bundleStatusTable.setName("BUNDLESTATUS_TABLE");
		bundleStatusTable.setSelectionBackground(new Color(204, 204, 255));
		bundleStatusTable.setSelectionForeground(Color.BLACK);
		bundleStatusTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

		// give actions a chance to update status when selection changed
		bundleStatusTable.getSelectionModel().addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) {
				return;
			}
			tool.contextChanged(BundleStatusComponentProvider.this);
		});

		// to allow custom cell renderers
		bundleStatusTable.setAutoCreateColumnsFromModel(false);

		configureTableColumns();
		filterPanel = new GTableFilterPanel<>(bundleStatusTable, bundleStatusTableModel);

		JScrollPane scrollPane = new JScrollPane(bundleStatusTable);
		scrollPane.getViewport().setBackground(bundleStatusTable.getBackground());

		panel.add(filterPanel, BorderLayout.SOUTH);
		panel.add(scrollPane, BorderLayout.CENTER);
		panel.setPreferredSize(new Dimension(800, 400));
	}

	private void configureTableColumns() {
		int skinnyWidth = 60;
		bundleStatusTableModel.activeColumn.setColumnRenderer(new BusyBooleanRenderer());
		bundleStatusTableModel.activeColumn.setColumnPreferredWidth(skinnyWidth);
		bundleStatusTableModel.enabledColumn.setColumnPreferredWidth(skinnyWidth);
		bundleStatusTableModel.typeColumn.setColumnPreferredWidth(90);
		bundleStatusTableModel.pathColumn.setColumnRenderer(new BundleFileRenderer());
	}

	private void addBundlesAction(String actionName, String description, Icon icon,
			Runnable runnable) {

		new ActionBuilder(actionName, this.getName()).popupMenuPath(description)
			.popupMenuIcon(icon)
			.popupMenuGroup(BUNDLE_GROUP)
			.toolBarIcon(icon)
			.toolBarGroup(BUNDLE_GROUP)
			.description(description)
			.enabled(false)
			.enabledWhen(context -> bundleStatusTable.getSelectedRows().length > 0)
			.onAction(context -> runnable.run())
			.buildAndInstallLocal(this);
	}

	private void createActions() {
		Icon icon = Icons.REFRESH_ICON;
		new ActionBuilder("RefreshBundles", this.getName()).popupMenuPath("Refresh all")
			.popupMenuIcon(icon)
			.popupMenuGroup(BUNDLE_LIST_GROUP)
			.toolBarIcon(icon)
			.toolBarGroup(BUNDLE_LIST_GROUP)
			.description("(re)Activate all enabled bundles in dependency order")
			.onAction(c -> doRefresh())
			.buildAndInstallLocal(this);

		addBundlesAction("ActivateBundles", "Activate bundle(s)",
			ResourceManager.loadImage("images/media-playback-start.png"), this::doActivateBundles);

		addBundlesAction("DeactivateBundles", "Deactivate bundle(s)",
			ResourceManager.loadImage("images/media-playback-stop.png"), this::doDeactivateBundles);

		addBundlesAction("CleanBundles", "Clean bundle(s)",
			ResourceManager.loadImage("images/erase16.png"), this::doClean);

		icon = ResourceManager.loadImage("images/Plus.png");
		new ActionBuilder("AddBundles", this.getName()).popupMenuPath("Add Bundle(s)")
			.popupMenuIcon(icon)
			.popupMenuGroup(BUNDLE_LIST_GROUP)
			.toolBarIcon(icon)
			.toolBarGroup(BUNDLE_LIST_GROUP)
			.description("Display file chooser to add bundles to list")
			.onAction(c -> showAddBundlesFileChooser())
			.buildAndInstallLocal(this);

		icon = ResourceManager.loadImage("images/edit-delete.png");
		new ActionBuilder("RemoveBundles", this.getName()).popupMenuPath("Remove bundle(s)")
			.popupMenuIcon(icon)
			.popupMenuGroup(BUNDLE_LIST_GROUP)
			.toolBarIcon(icon)
			.toolBarGroup(BUNDLE_LIST_GROUP)
			.description("Remove selected bundle(s) from the list")
			.enabledWhen(c -> bundleStatusTable.getSelectedRows().length > 0)
			.onAction(c -> doRemoveBundles())
			.buildAndInstallLocal(this);
	}

	/**
	 * get the currently selected rows and translate to model rows
	 * 
	 * @return selected model rows
	 */
	int[] getSelectedModelRows() {
		int[] selectedRows = bundleStatusTable.getSelectedRows();
		if (selectedRows == null) {
			return null;
		}
		return Arrays.stream(selectedRows).map(filterPanel::getModelRow).toArray();
	}

	private void doRefresh() {
		PrintWriter errOut = getTool().getService(ConsoleService.class).getStdErr();

		List<BundleStatus> statuses = bundleStatusTableModel.getModelData()
			.stream()
			.filter(BundleStatus::isEnabled)
			.collect(Collectors.toList());

		// clean them all..
		for (BundleStatus status : statuses) {
			GhidraBundle bundle = bundleHost.getExistingGhidraBundle(status.getFile());
			bundle.clean();
			status.setSummary("");
			try {
				bundleHost.deactivateSynchronously(bundle.getLocationIdentifier());
			}
			catch (GhidraBundleException | InterruptedException e) {
				e.printStackTrace(errOut);
			}
		}

		// then activate them all
		new TaskLauncher(new ActivateBundlesTask("activating", true, true, false, statuses),
			getComponent(), 1000);
	}

	private void doClean() {
		int[] selectedModelRows = getSelectedModelRows();
		boolean anythingCleaned = false;
		for (BundleStatus status : bundleStatusTableModel.getRowObjects(selectedModelRows)) {
			anythingCleaned |= bundleHost.getExistingGhidraBundle(status.getFile()).clean();
			if (!status.getSummary().isEmpty()) {
				status.setSummary("");
				anythingCleaned |= true;
			}
		}
		if (anythingCleaned) {
			bundleStatusTableModel.fireTableDataChanged();
			AnimationUtils.shakeComponent(getComponent());
		}
	}

	private void doRemoveBundles() {
		int[] selectedModelRows = getSelectedModelRows();
		if (selectedModelRows == null || selectedModelRows.length == 0) {
			return;
		}
		new TaskLauncher(new RemoveBundlesTask("removing bundles", getSelectedStatuses()),
			getComponent(), 1000);
	}

	private void showAddBundlesFileChooser() {
		if (fileChooser == null) {
			fileChooser = new GhidraFileChooser(panel);
			fileChooser.setMultiSelectionEnabled(true);
			fileChooser.setFileSelectionMode(GhidraFileChooserMode.FILES_AND_DIRECTORIES);
			fileChooser.setTitle("Select Bundle(s)");
			// fileChooser.setApproveButtonToolTipText(title);
			if (filter != null) {
				fileChooser.addFileFilter(new GhidraFileFilter() {
					@Override
					public String getDescription() {
						return filter.getDescription();
					}

					@Override
					public boolean accept(File f, GhidraFileChooserModel model) {
						return filter.accept(f, model);
					}
				});
			}
			String lastSelected = Preferences.getProperty(PREFENCE_LAST_SELECTED_BUNDLE);
			if (lastSelected != null) {
				File lastSelectedFile = new File(lastSelected);
				fileChooser.setSelectedFile(lastSelectedFile);
			}
		}
		else {
			String lastSelected = Preferences.getProperty(PREFENCE_LAST_SELECTED_BUNDLE);
			if (lastSelected != null) {
				File lastSelectedFile = new File(lastSelected);
				fileChooser.setSelectedFile(lastSelectedFile);
			}
			fileChooser.rescanCurrentDirectory();
		}

		List<File> files = fileChooser.getSelectedFiles();
		if (!files.isEmpty()) {
			Preferences.setProperty(PREFENCE_LAST_SELECTED_BUNDLE, files.get(0).getAbsolutePath());
			List<ResourceFile> resourceFiles =
				files.stream().map(ResourceFile::new).collect(Collectors.toUnmodifiableList());
			Collection<GhidraBundle> bundles = bundleHost.add(resourceFiles, true, false);

			new TaskLauncher(new Task("activating new bundles") {
				@Override
				public void run(TaskMonitor monitor) throws CancelledException {
					bundleHost.activateAll(bundles, monitor,
						getTool().getService(ConsoleService.class).getStdErr());
				}
			}, getComponent(), 1000);
		}
	}

	protected List<BundleStatus> getSelectedStatuses() {
		return bundleStatusTableModel.getRowObjects(getSelectedModelRows());
	}

	protected void doActivateBundles() {
		new TaskLauncher(
			new ActivateBundlesTask("activating", true, true, false, getSelectedStatuses()),
			getComponent(), 1000);
	}

	protected void doDeactivateBundles() {
		new TaskLauncher(
			new DeactivateBundlesTask("deactivating", true, true, false, getSelectedStatuses()),
			getComponent(), 1000);
	}

	protected void doActivateDeactivateBundle(BundleStatus status, boolean activate) {
		status.setBusy(true);
		notifyTableRowChanged(status);
		new TaskLauncher(
			new ActivateDeactivateBundleTask(
				(activate ? "Activating" : "Deactivating ") + " bundle...", status, activate),
			null, 1000);
	}

	private void notifyTableRowChanged(BundleStatus status) {
		int modelRowIndex = bundleStatusTableModel.getRowIndex(status);
		int viewRowIndex = filterPanel.getViewRow(modelRowIndex);
		bundleStatusTable
			.notifyTableChanged(new TableModelEvent(bundleStatusTableModel, viewRowIndex));
	}

	private void notifyTableDataChanged() {
		bundleStatusTable.notifyTableChanged(new TableModelEvent(bundleStatusTableModel));
	}

	@Override
	public JComponent getComponent() {
		return panel;
	}

	/**
	 * cleanup this component
	 */
	public void dispose() {
		bundleStatusTable.dispose();
	}

	void selectModelRow(int modelRowIndex) {
		bundleStatusTable.selectRow(filterPanel.getViewRow(modelRowIndex));
	}

	/**
	 * This is for testing only!  during normal execution, statuses are only added through BundleHostListener bundle(s) added events.
	 * 
	 * <p>each new bundle will be enabled and writable
	 * 
	 * @param bundleFiles the files to use
	 */
	public void setBundleFilesForTesting(List<ResourceFile> bundleFiles) {
		bundleStatusTableModel.setModelData(bundleFiles.stream()
			.map(f -> new BundleStatus(f, true, false, null))
			.collect(Collectors.toList()));
	}

	private final class RemoveBundlesTask extends Task {
		private final DeactivateBundlesTask deactivateBundlesTask;
		private final List<BundleStatus> statuses;

		private RemoveBundlesTask(String title, List<BundleStatus> statuses) {
			super(title);
			this.deactivateBundlesTask =
				new DeactivateBundlesTask("deactivating", true, true, false, statuses);
			this.statuses = statuses;
		}

		@Override
		public void run(TaskMonitor monitor) throws CancelledException {
			deactivateBundlesTask.run(monitor);
			if (!monitor.isCancelled()) {
				// partition bundles into system (bundles.get(true)) and non-system (bundles.get(false)).
				Map<Boolean, List<GhidraBundle>> bundles = statuses.stream()
					.map(bs -> bundleHost.getExistingGhidraBundle(bs.getFile()))
					.collect(Collectors.partitioningBy(GhidraBundle::isSystemBundle));

				List<GhidraBundle> systemBundles = bundles.get(true);
				if (!systemBundles.isEmpty()) {
					StringBuilder stringBuilder = new StringBuilder();
					for (GhidraBundle bundle : systemBundles) {
						bundleHost.disable(bundle);
						stringBuilder.append(bundle.getFile() + "\n");
					}
					Msg.showWarn(this, BundleStatusComponentProvider.this.getComponent(),
						"Unabled to remove",
						"System bundles cannot be removed:\n" + stringBuilder.toString());
				}
				bundleHost.remove(bundles.get(false));
			}
		}
	}

	private class ActivateBundlesTask extends Task {
		private final List<BundleStatus> statuses;

		private ActivateBundlesTask(String title, boolean canCancel, boolean hasProgress,
				boolean isModal, List<BundleStatus> statuses) {
			super(title, canCancel, hasProgress, isModal);
			this.statuses = statuses;
		}

		@Override
		public void run(TaskMonitor monitor) throws CancelledException {
			// suppress RowObjectSelectionManager repairs until after we're done
			bundleStatusTable.chill();

			List<GhidraBundle> bundles = new ArrayList<>();
			for (BundleStatus status : statuses) {
				GhidraBundle bundle = bundleHost.getExistingGhidraBundle(status.getFile());
				if (!(bundle instanceof GhidraPlaceholderBundle)) {
					status.setBusy(true);
					bundleHost.enable(bundle);
					bundles.add(bundle);
				}
			}
			notifyTableDataChanged();

			bundleHost.activateAll(bundles, monitor,
				getTool().getService(ConsoleService.class).getStdErr());

			boolean anybusy = false;
			for (BundleStatus status : statuses) {
				if (status.isBusy()) {
					anybusy = true;
					status.setBusy(false);
				}
			}
			if (anybusy) {
				notifyTableDataChanged();
			}

			bundleStatusTable.thaw();
		}
	}

	private class DeactivateBundlesTask extends Task {
		final List<BundleStatus> statuses;

		private DeactivateBundlesTask(String title, boolean canCancel, boolean hasProgress,
				boolean isModal, List<BundleStatus> statuses) {
			super(title, canCancel, hasProgress, isModal);
			this.statuses = statuses;
		}

		@Override
		public void run(TaskMonitor monitor) throws CancelledException {
			List<GhidraBundle> bundles = statuses.stream()
				.filter(status -> status.isActive())
				.map(status -> bundleHost.getExistingGhidraBundle(status.getFile()))
				.collect(Collectors.toList());

			monitor.setMaximum(bundles.size());
			for (GhidraBundle bundle : bundles) {
				try {
					bundleHost.deactivateSynchronously(bundle.getLocationIdentifier());
				}
				catch (GhidraBundleException | InterruptedException e) {
					ConsoleService console = getTool().getService(ConsoleService.class);
					e.printStackTrace(console.getStdErr());
				}
				monitor.incrementProgress(1);
			}
		}
	}

	/*
	 * Activating/deactivating a single bundle doesn't require resolving dependents,
	 * so this task is slightly different from the others.
	 */
	private class ActivateDeactivateBundleTask extends Task {
		private final BundleStatus status;
		private final boolean activate;

		private ActivateDeactivateBundleTask(String title, BundleStatus status, boolean activate) {
			super(title);
			this.status = status;
			this.activate = activate;
		}

		@Override
		public void run(TaskMonitor monitor) throws CancelledException {
			ConsoleService console = getTool().getService(ConsoleService.class);
			try {
				GhidraBundle bundle = bundleHost.getExistingGhidraBundle(status.getFile());
				if (activate) {
					bundle.build(console.getStdErr());
					bundleHost.activateSynchronously(bundle.getLocationIdentifier());
				}
				else { // deactivate
					bundleHost.deactivateSynchronously(bundle.getLocationIdentifier());
				}
			}
			catch (Exception e) {
				e.printStackTrace(console.getStdErr());
			}
			finally {
				status.setBusy(false);
				notifyTableRowChanged(status);
			}
		}
	}

	/**
	 * Listener that responds to change requests from the {@link BundleStatusTableModel}.
	 */
	private class MyBundleStatusChangeRequestListener implements BundleStatusChangeRequestListener {
		@Override
		public void bundleEnablementChangeRequest(BundleStatus status, boolean enabled) {
			GhidraBundle bundle = bundleHost.getExistingGhidraBundle(status.getFile());
			if (bundle instanceof GhidraPlaceholderBundle) {
				return;
			}
			if (enabled) {
				bundleHost.enable(bundle);
			}
			else {
				if (status.isActive()) {
					doActivateDeactivateBundle(status, false);
				}
				bundleHost.disable(bundle);
			}
		}

		@Override
		public void bundleActivationChangeRequest(BundleStatus status, boolean newValue) {
			if (status.isEnabled()) {
				doActivateDeactivateBundle(status, newValue);
			}
		}
	}

	private class BusyBooleanRenderer extends GBooleanCellRenderer
			implements AbstractWrapperTypeColumnRenderer<Boolean> {
		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			BundleStatus status = (BundleStatus) data.getRowObject();
			Component component = super.getTableCellRendererComponent(data);
			if (status.isBusy()) {
				cb.setVisible(false);
				cb.setEnabled(false);
				setHorizontalAlignment(SwingConstants.CENTER);
				setText("...");
			}
			else {
				cb.setVisible(true);
				cb.setEnabled(true);
				setText("");
			}
			return component;
		}

	}

	private class BundleFileRenderer extends AbstractGColumnRenderer<ResourceFile> {

		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			BundleStatus status = (BundleStatus) data.getRowObject();
			ResourceFile file = (ResourceFile) data.getValue();
			JLabel label = (JLabel) super.getTableCellRendererComponent(data);
			label.setFont(defaultFont.deriveFont(defaultFont.getStyle() | Font.BOLD));
			label.setText(Path.toPathString(file));
			GhidraBundle bundle = bundleHost.getExistingGhidraBundle(file);
			if (bundle == null || bundle instanceof GhidraPlaceholderBundle || !file.exists()) {
				label.setBackground(Color.RED);
				label.setForeground(Color.BLACK);
			}
			else {
				if (status.isBusy()) {
					label.setForeground(Color.GRAY);
				}
				else if (!status.isEnabled()) {
					label.setForeground(Color.BLACK);
				}
				else if (status.isActive()) {
					label.setForeground(DARK_GREEN);
				}
				else {
					label.setForeground(DARK_RED);
				}
			}
			return label;
		}

		@Override
		public String getFilterString(ResourceFile file, Settings settings) {
			return Path.toPathString(file);
		}

	}
}