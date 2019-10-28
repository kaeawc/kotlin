// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.impl;

import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.Executor;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.target.LanguageRuntimeType;
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile;
import com.intellij.execution.target.TargetEnvironmentsConfigurable;
import com.intellij.execution.target.TargetEnvironmentsManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.application.Experiments;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.options.SettingsEditorListener;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.VcsIgnoreManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.project.ProjectKt;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemIndependent;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public final class SingleConfigurationConfigurable<Config extends RunConfiguration>
  extends BaseRCSettingsConfigurable {
  public static final DataKey<String> RUN_ON_TARGET_NAME_KEY = DataKey.create("RunOnTargetName");

  private static final Logger LOG = Logger.getInstance(SingleConfigurationConfigurable.class);

  private final PlainDocument myNameDocument = new PlainDocument();
  @Nullable private final Executor myExecutor;

  private ValidationResult myLastValidationResult = null;
  private boolean myValidationResultValid = false;
  private MyValidatableComponent myComponent;
  private final String myDisplayName;
  private final String myHelpTopic;
  private final boolean myBrokenConfiguration;
  private boolean myStoreProjectConfiguration;
  private boolean myIsAllowRunningInParallel = false;
  private String myDefaultTargetName;
  private String myFolderName;
  private boolean myChangingNameFromCode;

  private SingleConfigurationConfigurable(@NotNull RunnerAndConfigurationSettings settings, @Nullable Executor executor) {
    super(new ConfigurationSettingsEditorWrapper(settings), settings);

    myExecutor = executor;

    final Config configuration = getConfiguration();
    myDisplayName = getSettings().getName();
    myHelpTopic = configuration.getType().getHelpTopic();

    myBrokenConfiguration = !configuration.getType().isManaged();
    setFolderName(getSettings().getFolderName());

    setNameText(configuration.getName());
    myNameDocument.addDocumentListener(new DocumentAdapter() {
      @Override
      public void textChanged(@NotNull DocumentEvent event) {
        setModified(true);
        if (!myChangingNameFromCode) {
          RunConfiguration runConfiguration = getSettings().getConfiguration();
          if (runConfiguration instanceof LocatableConfigurationBase) {
            ((LocatableConfigurationBase) runConfiguration).setNameChangedByUser(true);
          }
        }
      }
    });

    getEditor().addSettingsEditorListener(new SettingsEditorListener<RunnerAndConfigurationSettings>() {
      @Override
      public void stateChanged(@NotNull SettingsEditor<RunnerAndConfigurationSettings> settingsEditor) {
        myValidationResultValid = false;
      }
    });
  }

  @NotNull
  public static <Config extends RunConfiguration> SingleConfigurationConfigurable<Config> editSettings(@NotNull RunnerAndConfigurationSettings settings, @Nullable Executor executor) {
    SingleConfigurationConfigurable<Config> configurable = new SingleConfigurationConfigurable<>(settings, executor);
    configurable.reset();
    return configurable;
  }

  @Override
  void applySnapshotToComparison(RunnerAndConfigurationSettings original, RunnerAndConfigurationSettings snapshot) {
    snapshot.setTemporary(original.isTemporary());
    snapshot.setName(getNameText());
    RunConfiguration runConfiguration = snapshot.getConfiguration();
    runConfiguration.setAllowRunningInParallel(myIsAllowRunningInParallel);
    if (runConfiguration instanceof TargetEnvironmentAwareRunProfile) {
      ((TargetEnvironmentAwareRunProfile)runConfiguration).setDefaultTargetName(myDefaultTargetName);
    }
    snapshot.setFolderName(myFolderName);
  }

  @Override
  boolean isSnapshotSpecificallyModified(@NotNull RunnerAndConfigurationSettings original, @NotNull RunnerAndConfigurationSettings snapshot) {
    return original.isShared() != myStoreProjectConfiguration;
  }

  @Override
  public void apply() throws ConfigurationException {
    RunnerAndConfigurationSettings settings = getSettings();

    RunConfiguration runConfiguration = settings.getConfiguration();
    settings.setName(getNameText());
    runConfiguration.setAllowRunningInParallel(myIsAllowRunningInParallel);
    if (runConfiguration instanceof TargetEnvironmentAwareRunProfile) {
      ((TargetEnvironmentAwareRunProfile)runConfiguration).setDefaultTargetName(myDefaultTargetName);
    }
    settings.setFolderName(myFolderName);
    settings.setShared(myStoreProjectConfiguration);
    super.apply();
    RunManagerImpl.getInstanceImpl(runConfiguration.getProject()).addConfiguration(settings);
  }

  @Override
  public void reset() {
    RunnerAndConfigurationSettings configuration = getSettings();
    if (configuration instanceof RunnerAndConfigurationSettingsImpl) {
      configuration = ((RunnerAndConfigurationSettingsImpl)configuration).clone();
    }
    setNameText(configuration.getName());
    super.reset();
    if (myComponent == null) {
      myComponent = new MyValidatableComponent(configuration.getConfiguration().getProject());
    }
    myComponent.doReset(configuration);
  }

  void updateWarning() {
    myValidationResultValid = false;
    if (myComponent != null) {
      myComponent.updateWarning();
    }
  }

  @Override
  public final JComponent createComponent() {
    myComponent.myNameText.setEnabled(!myBrokenConfiguration);
    JComponent result = myComponent.getWholePanel();
    DataManager.registerDataProvider(result, dataId -> {
      if (ConfigurationSettingsEditorWrapper.CONFIGURATION_EDITOR_KEY.is(dataId)) {
        return getEditor();
      }
      if (RUN_ON_TARGET_NAME_KEY.is(dataId)) {
        return ((RunOnTargetComboBox)myComponent.myRunOnComboBox).getSelectedTargetName();
      }
      return null;
    });
    return result;
  }

  final JComponent getValidationComponent() {
    return myComponent.myValidationPanel;
  }

  public boolean isStoreProjectConfiguration() {
    return myStoreProjectConfiguration;
  }

  @Nullable
  private ValidationResult getValidationResult() {
    if (!myValidationResultValid) {
      myLastValidationResult = null;
      RunnerAndConfigurationSettings snapshot = null;
      try {
        snapshot = createSnapshot(false);
        snapshot.setName(getNameText());
        snapshot.checkSettings(myExecutor);
        for (Executor executor : Executor.EXECUTOR_EXTENSION_NAME.getExtensionList()) {
          ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), snapshot.getConfiguration());
          if (runner != null) {
            checkConfiguration(runner, snapshot);
          }
        }
      }
      catch (ConfigurationException e) {
        myLastValidationResult = createValidationResult(snapshot, e);
      }

      myValidationResultValid = true;
    }
    return myLastValidationResult;
  }

  private ValidationResult createValidationResult(RunnerAndConfigurationSettings snapshot, ConfigurationException e) {
    if (!e.shouldShowInDumbMode() && DumbService.isDumb(getConfiguration().getProject())) return null;

    return new ValidationResult(
      e.getLocalizedMessage(),
      e instanceof RuntimeConfigurationException ? e.getTitle() : ExecutionBundle.message("invalid.data.dialog.title"),
      getQuickFix(snapshot, e));
  }

  @Nullable
  private Runnable getQuickFix(RunnerAndConfigurationSettings snapshot, ConfigurationException exception) {
    Runnable quickFix = exception.getQuickFix();
    if (quickFix != null && snapshot != null) {
      return () -> {
        quickFix.run();
        getEditor().resetFrom(snapshot);
      };
    }
    return quickFix;
  }

  private static void checkConfiguration(@NotNull ProgramRunner<?> runner, @NotNull RunnerAndConfigurationSettings snapshot)
    throws RuntimeConfigurationException {
    RunnerSettings runnerSettings = snapshot.getRunnerSettings(runner);
    ConfigurationPerRunnerSettings configurationSettings = snapshot.getConfigurationSettings(runner);
    try {
      runner.checkConfiguration(runnerSettings, configurationSettings);
    }
    catch (AbstractMethodError e) {
      //backward compatibility
    }
  }

  @Override
  public final void disposeUIResources() {
    super.disposeUIResources();
    myComponent = null;
  }

  public final String getNameText() {
    try {
      return myNameDocument.getText(0, myNameDocument.getLength());
    }
    catch (BadLocationException e) {
      LOG.error(e);
      return "";
    }
  }

  public final void addNameListener(DocumentListener listener) {
    myNameDocument.addDocumentListener(listener);
  }

  public final void addSharedListener(ChangeListener changeListener) {
    myComponent.myCbStoreProjectConfiguration.addChangeListener(changeListener);
  }

  public final void setNameText(final String name) {
    myChangingNameFromCode = true;
    try {
      try {
        if (!myNameDocument.getText(0, myNameDocument.getLength()).equals(name)) {
          myNameDocument.replace(0, myNameDocument.getLength(), name, null);
        }
      }
      catch (BadLocationException e) {
        LOG.error(e);
      }
    }
    finally {
      myChangingNameFromCode = false;
    }
  }

  public final boolean isValid() {
    return getValidationResult() == null;
  }

  public final JTextField getNameTextField() {
    return myComponent.myNameText;
  }

  @Override
  public String getDisplayName() {
    return myDisplayName;
  }

  @Override
  public String getHelpTopic() {
    return myHelpTopic;
  }

  @NotNull
  public Config getConfiguration() {
    //noinspection unchecked
    return (Config)getSettings().getConfiguration();
  }

  @NotNull
  public RunnerAndConfigurationSettings createSnapshot(boolean cloneBeforeRunTasks) throws ConfigurationException {
    RunnerAndConfigurationSettings snapshot = getEditor().getSnapshot();
    RunConfiguration runConfiguration = snapshot.getConfiguration();
    runConfiguration.setAllowRunningInParallel(myIsAllowRunningInParallel);
    if (runConfiguration instanceof TargetEnvironmentAwareRunProfile) {
      ((TargetEnvironmentAwareRunProfile)runConfiguration).setDefaultTargetName(myDefaultTargetName);
    }
    if (cloneBeforeRunTasks) {
      RunManagerImplKt.cloneBeforeRunTasks(runConfiguration);
    }
    return snapshot;
  }

  @Override
  public String toString() {
    return myDisplayName;
  }

  public void setFolderName(@Nullable String folderName) {
    if (!Comparing.equal(myFolderName, folderName)) {
      myFolderName = folderName;
      setModified(true);
    }
  }

  @Nullable
  public String getFolderName() {
    return myFolderName;
  }

  private class MyValidatableComponent {
    private JLabel myNameLabel;
    private JTextField myNameText;
    private JComponent myWholePanel;
    private JPanel myComponentPlace;
    private JBLabel myWarningLabel;
    private JButton myFixButton;
    private JSeparator mySeparator;
    private JCheckBox myCbStoreProjectConfiguration;
    private JBCheckBox myIsAllowRunningInParallelCheckBox;
    private JPanel myValidationPanel;
    private JBScrollPane myJBScrollPane;
    private JPanel myCbStoreProjectConfigurationPanel;

    private ComboBox myRunOnComboBox;
    private JLabel myManageTargetsLabel;
    private JPanel myRunOnPanel;
    private JPanel myRunOnPanelInner;

    private final ComponentValidator myCbStoreProjectConfigurationValidator;

    private Runnable myQuickFix = null;

    MyValidatableComponent(@NotNull Project project) {
      myNameLabel.setLabelFor(myNameText);
      myNameText.setDocument(myNameDocument);

      getEditor().addSettingsEditorListener(settingsEditor -> updateWarning());
      myWarningLabel.setCopyable(true);
      myWarningLabel.setAllowAutoWrapping(true);
      myWarningLabel.setIcon(AllIcons.General.BalloonError);

      myComponentPlace.setLayout(new GridBagLayout());
      myComponentPlace.add(getEditorComponent(),
                           new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0, GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH,
                                                  JBUI.emptyInsets(), 0, 0));
      myComponentPlace.doLayout();
      myFixButton.setIcon(AllIcons.Actions.QuickfixBulb);
      updateWarning();
      myFixButton.addActionListener(new ActionListener() {
        @Override
        public void actionPerformed(final ActionEvent e) {
          if (myQuickFix == null) {
            return;
          }
          myQuickFix.run();
          myValidationResultValid = false;
          updateWarning();
        }
      });
      myCbStoreProjectConfigurationValidator =
        new ComponentValidator(getEditor()).withValidator(() -> getStoreProjectConfigurationValidationInfo())
          .withHyperlinkListener(new HyperlinkListener() {
            @Override
            public void hyperlinkUpdate(HyperlinkEvent e) {
              if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                VcsIgnoreManager.getInstance(getConfiguration().getProject()).removeRunConfigurationFromVcsIgnore(getConfiguration().getName());
                myCbStoreProjectConfigurationValidator.revalidate();
              }
            }
          })
          .installOn(myCbStoreProjectConfiguration);
      myCbStoreProjectConfiguration.addActionListener(e -> {
        setModified(true);
        myStoreProjectConfiguration = myCbStoreProjectConfiguration.isSelected();
        myCbStoreProjectConfigurationValidator.revalidate();
      });
      myIsAllowRunningInParallelCheckBox.addActionListener(e -> {
        setModified(true);
        myIsAllowRunningInParallel = myIsAllowRunningInParallelCheckBox.isSelected();
      });
      myJBScrollPane.setBorder(JBUI.Borders.empty());
      myJBScrollPane.setViewportBorder(JBUI.Borders.empty());

      ComponentPanelBuilder componentPanelBuilder = UI.PanelFactory.panel(myCbStoreProjectConfiguration);
      @SystemIndependent VirtualFile projectFile = project.getProjectFile();
      if (projectFile != null) {
        componentPanelBuilder.withTooltip(
          ProjectKt.isDirectoryBased(project)
          ? ExecutionBundle.message("run.configuration.share.hint", ".idea folder")
          : ExecutionBundle.message("run.configuration.share.hint", projectFile.getName())
        );
      }
      componentPanelBuilder.addToPanel(myCbStoreProjectConfigurationPanel,
                                       new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0,
                                                              GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH,
                                                              JBUI.emptyInsets(), 0, 0), false);

      myRunOnPanel.setBorder(JBUI.Borders.emptyLeft(5));
      UI.PanelFactory.panel(myRunOnPanelInner)
        .withLabel("Run on:")
        .withComment(ExecutionBundle.message("edit.run.configuration.run.configuration.run.on.comment"))
        .addToPanel(myRunOnPanel, new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0,
                                                         GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH,
                                                         JBUI.emptyInsets(), 0, 0));
      JLabel runOnLabel = UIUtil.findComponentOfType(myRunOnPanel, JLabel.class);
      if (runOnLabel != null) {
        runOnLabel.setLabelFor(myRunOnComboBox);
        Dimension nameSize = myNameLabel.getPreferredSize();
        Dimension runOnSize = runOnLabel.getPreferredSize();
        double width = Math.max(nameSize.getWidth(), runOnSize.getWidth());
        myNameLabel.setPreferredSize(new Dimension((int)width, (int)nameSize.getHeight()));
        runOnLabel.setPreferredSize(new Dimension((int)width, (int)runOnSize.getHeight()));
      }

      myRunOnComboBox.addActionListener(e -> {
        String chosenTarget = ((RunOnTargetComboBox)myRunOnComboBox).getSelectedTargetName();
        if (!StringUtil.equals(myDefaultTargetName, chosenTarget)) {
          setModified(true);
          myDefaultTargetName = chosenTarget;
        }
      });
    }

    @Nullable
    private ValidationInfo getStoreProjectConfigurationValidationInfo() {
      Project project = getConfiguration().getProject();
      @SystemIndependent VirtualFile projectFile = project.getProjectFile();
      if (projectFile == null) return null;
      if (!myCbStoreProjectConfiguration.isSelected()) return null;

      if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
        String fileAddToVcs = ProjectKt.isDirectoryBased(project)
                              ? Project.DIRECTORY_STORE_FOLDER + "/runConfigurations"
                              : projectFile.getName();
        return new ValidationInfo(ExecutionBundle.message("run.configuration.share.vcs.disabled", fileAddToVcs),
                                  myCbStoreProjectConfiguration).asWarning();
      }
      else if (VcsIgnoreManager.getInstance(getConfiguration().getProject()).isRunConfigurationVcsIgnored(getConfiguration().getName())) {
        return new ValidationInfo(ExecutionBundle.message("run.configuration.share.vcs.ignored", getConfiguration().getName()),
                                  myCbStoreProjectConfiguration).asWarning();
      }
      return null;
    }

    private void doReset(RunnerAndConfigurationSettings settings) {
      RunConfiguration configuration = settings.getConfiguration();
      boolean isManagedRunConfiguration = configuration.getType().isManaged();
      myStoreProjectConfiguration = settings.isShared();
      myCbStoreProjectConfiguration.setEnabled(isManagedRunConfiguration);
      myCbStoreProjectConfiguration.setSelected(myStoreProjectConfiguration);
      myCbStoreProjectConfiguration.setVisible(!settings.isTemplate());
      myCbStoreProjectConfigurationValidator.revalidate();

      boolean targetAware = configuration instanceof TargetEnvironmentAwareRunProfile && Experiments.getInstance().isFeatureEnabled("runtime.environments");
      myRunOnPanel.setVisible(targetAware);
      if (targetAware) {
        String defaultTargetName = ((TargetEnvironmentAwareRunProfile)configuration).getDefaultTargetName();
        LanguageRuntimeType<?> defaultRuntime = ((TargetEnvironmentAwareRunProfile)configuration).getDefaultLanguageRuntimeType();
        ((RunOnTargetComboBox)myRunOnComboBox).setDefaultLanguageRuntimeTime(defaultRuntime);
        resetRunOnComboBox(defaultTargetName);
        myDefaultTargetName = defaultTargetName;
      }

      myIsAllowRunningInParallel = configuration.isAllowRunningInParallel();
      myIsAllowRunningInParallelCheckBox.setEnabled(isManagedRunConfiguration);
      myIsAllowRunningInParallelCheckBox.setSelected(myIsAllowRunningInParallel);
      myIsAllowRunningInParallelCheckBox.setVisible(settings.getFactory().getSingletonPolicy().isPolicyConfigurable());
    }

    private void resetRunOnComboBox(@Nullable String targetNameToChoose) {
      ((RunOnTargetComboBox)myRunOnComboBox).initModel();
      ((RunOnTargetComboBox)myRunOnComboBox).addTargets(TargetEnvironmentsManager.getInstance().getTargets().resolvedConfigs());
      ((RunOnTargetComboBox)myRunOnComboBox).selectTarget(targetNameToChoose);
    }

    public final JComponent getWholePanel() {
      return myWholePanel;
    }

    public JComponent getEditorComponent() {
      return getEditor().getComponent();
    }

    @Nullable
    public ValidationResult getValidationResult() {
      return SingleConfigurationConfigurable.this.getValidationResult();
    }

    private void updateWarning() {
      final ValidationResult configurationException = getValidationResult();

      if (configurationException != null) {
        mySeparator.setVisible(true);
        myWarningLabel.setVisible(true);
        myWarningLabel.setText(generateWarningLabelText(configurationException));
        final Runnable quickFix = configurationException.getQuickFix();
        if (quickFix == null) {
          myFixButton.setVisible(false);
        }
        else {
          myFixButton.setVisible(true);
          myQuickFix = quickFix;
        }
        myValidationPanel.setVisible(true);
      }
      else {
        mySeparator.setVisible(false);
        myWarningLabel.setVisible(false);
        myFixButton.setVisible(false);
        myValidationPanel.setVisible(false);
      }
    }

    @NonNls
    private String generateWarningLabelText(final ValidationResult configurationException) {
      return "<html><body><b>" + configurationException.getTitle() + ": </b>" + configurationException.getMessage() + "</body></html>";
    }

    private void createUIComponents() {
      myComponentPlace = new NonOpaquePanel();
      myRunOnComboBox = new RunOnTargetComboBox(myProject);
      myManageTargetsLabel = LinkLabel.create(ExecutionBundle.message("edit.run.configuration.run.configuration.manage.targets.label"), () -> {
            String selectedName = ((RunOnTargetComboBox)myRunOnComboBox).getSelectedTargetName();
            TargetEnvironmentsConfigurable configurable = new TargetEnvironmentsConfigurable(myProject, selectedName);
            if (ShowSettingsUtil.getInstance().editConfigurable(myWholePanel, configurable)) {
              resetRunOnComboBox(selectedName);
            }
        });
      myJBScrollPane = new JBScrollPane(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        @Override
        public Dimension getMinimumSize() {
          Dimension d = super.getMinimumSize();
          JViewport viewport = getViewport();
          if (viewport != null) {
            Component view = viewport.getView();
            if (view instanceof Scrollable) {
              d.width = ((Scrollable)view).getPreferredScrollableViewportSize().width;
            }
            if (view != null) {
              d.width = view.getMinimumSize().width;
            }
          }
          d.height = Math.max(d.height, JBUIScale.scale(400));
          return d;
        }
      };
    }
  }
}
