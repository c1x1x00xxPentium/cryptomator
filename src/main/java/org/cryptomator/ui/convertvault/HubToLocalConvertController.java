package org.cryptomator.ui.convertvault;

import com.google.common.base.Preconditions;
import org.cryptomator.common.Passphrase;
import org.cryptomator.common.vaults.Vault;
import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptofs.common.BackupHelper;
import org.cryptomator.cryptolib.api.CryptoException;
import org.cryptomator.cryptolib.api.MasterkeyLoader;
import org.cryptomator.cryptolib.api.MasterkeyLoadingFailedException;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.cryptomator.ui.changepassword.NewPasswordController;
import org.cryptomator.ui.common.FxController;
import org.cryptomator.ui.recoverykey.RecoveryKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.stage.Stage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static org.cryptomator.common.Constants.DEFAULT_KEY_ID;
import static org.cryptomator.common.Constants.MASTERKEY_BACKUP_SUFFIX;
import static org.cryptomator.common.Constants.MASTERKEY_FILENAME;
import static org.cryptomator.common.Constants.VAULTCONFIG_FILENAME;

public class HubToLocalConvertController implements FxController {

	private static final Logger LOG = LoggerFactory.getLogger(HubToLocalConvertController.class);

	private final Stage window;
	private final Vault vault;
	private final StringProperty recoveryKey;
	private final RecoveryKeyFactory recoveryKeyFactory;
	private final MasterkeyFileAccess masterkeyFileAccess;
	private final ExecutorService backgroundExecutorService;
	private final BooleanProperty isConverting;

	@FXML
	NewPasswordController newPasswordController;

	@Inject
	public HubToLocalConvertController(@ConvertVaultWindow Stage window, @ConvertVaultWindow Vault vault, @ConvertVaultWindow StringProperty recoveryKey, RecoveryKeyFactory recoveryKeyFactory, MasterkeyFileAccess masterkeyFileAccess, ExecutorService backgroundExecutorService) {
		this.window = window;
		this.vault = vault;
		this.recoveryKey = recoveryKey;
		this.recoveryKeyFactory = recoveryKeyFactory;
		this.masterkeyFileAccess = masterkeyFileAccess;
		this.backgroundExecutorService = backgroundExecutorService;
		this.isConverting = new SimpleBooleanProperty(false);
	}

	@FXML
	public void initialize() {
	}

	@FXML
	public void close() {
		window.close();
	}

	@FXML
	public void convert() {
		Preconditions.checkState(newPasswordController.isGoodPassword());
		LOG.info("Converting hub vault {} to local", vault.getPath());
		CompletableFuture.runAsync(() -> isConverting.setValue(true), Platform::runLater) //
				.thenRunAsync(this::convertInternal, backgroundExecutorService) //TODO: which executor is used?
				.whenCompleteAsync((result, exception) -> {
					isConverting.setValue(false);
					if (exception == null) { //TODO: check, how the exceptions are wrapped
						LOG.info("Conversion of vault {} succeeded.", vault.getPath());
					} else {
						LOG.error("Conversion of vault {} failed.", vault.getPath(), exception);
					}
				}, Platform::runLater); //
		//window.setScene(resetPasswordScene.get());
	}

	//visible for testing
	void convertInternal() throws CompletionException, IllegalArgumentException {
		var passphrase = newPasswordController.getNewPassword();
		try {
			recoveryKeyFactory.newMasterkeyFileWithPassphrase(vault.getPath(), recoveryKey.get(), passphrase);
			LOG.debug("Successfully created masterkey file for vault {}", vault.getPath());
			backupHubConfig(vault.getPath().resolve(VAULTCONFIG_FILENAME));
			replaceWithLocalConfig(passphrase);
		} catch (MasterkeyLoadingFailedException e) {
			throw new CompletionException(new IOException("Vault conversion failed", e));
		} catch (IOException e) {
			throw new CompletionException("Vault conversion failed", e);
		} finally {
			passphrase.destroy();
		}
	}

	//visible for testing
	void backupHubConfig(Path hubConfigPath) throws IOException {
		byte[] hubConfigBytes = Files.readAllBytes(hubConfigPath);
		Path backupPath = vault.getPath().resolve(VAULTCONFIG_FILENAME + BackupHelper.generateFileIdSuffix(hubConfigBytes) + MASTERKEY_BACKUP_SUFFIX);
		Files.move(hubConfigPath, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); //TODO: should this be an atomic move?
		LOG.debug("Successfully created vault config backup {} for vault {}", backupPath.getFileName(), vault.getPath());
	}

	//visible for testing
	void replaceWithLocalConfig(Passphrase passphrase) throws IOException, MasterkeyLoadingFailedException {
		var unverifiedVaultConfig = vault.getVaultConfigCache().get();
		try (var masterkey = masterkeyFileAccess.load(vault.getPath().resolve(MASTERKEY_FILENAME), passphrase)) {
			var vaultConfig = unverifiedVaultConfig.verify(masterkey.getEncoded(), unverifiedVaultConfig.allegedVaultVersion());
			MasterkeyLoader loader = ignored -> masterkey.copy();
			CryptoFileSystemProperties fsProps = CryptoFileSystemProperties.cryptoFileSystemProperties() //
					.withCipherCombo(vaultConfig.getCipherCombo()) //
					.withKeyLoader(loader) //
					.build();
			CryptoFileSystemProvider.initialize(vault.getPath(), fsProps, DEFAULT_KEY_ID);
		}
	}

	/* Getter/Setter */

	public NewPasswordController getNewPasswordController() {
		return newPasswordController;
	}

}
