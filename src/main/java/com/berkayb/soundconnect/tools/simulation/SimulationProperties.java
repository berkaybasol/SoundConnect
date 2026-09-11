package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.shared.validation.BcryptPasswordLength;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Explicit, fail-closed activation contract for the disposable local simulation.
 *
 * <p>The bean only exists when {@code app.simulation.enabled=true}. Defaults are
 * intentionally safe: the runtime is disabled and destructive reset is not
 * acknowledged.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.simulation")
public class SimulationProperties {

	private boolean enabled;

	@NotNull(message = "app.simulation.mode is required")
	private SimulationMode mode = SimulationMode.PAUSE;

	@Min(value = 1, message = "app.simulation.max-accounts must be at least 1")
	@Max(value = 50, message = "app.simulation.max-accounts must not exceed 50")
	private int maxAccounts = 50;

	@NotBlank(message = "app.simulation.email-suffix is required")
	@Size(max = 200, message = "app.simulation.email-suffix is too long")
	@Pattern(
			regexp = "(?i)^@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.invalid$",
			message = "app.simulation.email-suffix must be an @domain.invalid suffix"
	)
	private String emailSuffix = "@soundconnect.invalid";

	@NotBlank(message = "app.simulation.expected-postgresql-database is required")
	@Size(max = 63, message = "app.simulation.expected-postgresql-database is too long")
	@Pattern(
			regexp = "[A-Za-z0-9_][A-Za-z0-9_$-]*",
			message = "app.simulation.expected-postgresql-database has an invalid name"
	)
	private String expectedPostgresqlDatabase = "";

	private boolean destructiveResetAcknowledged;

	@BcryptPasswordLength
	private String commonPassword;

	@NotNull(message = "app.simulation.report-directory is required")
	private Path reportDirectory = Path.of("build", "reports", "simulation");

	@Min(value = 1, message = "app.simulation.mail-capture-capacity must be at least 1")
	@Max(value = 5_000, message = "app.simulation.mail-capture-capacity must not exceed 5000")
	private int mailCaptureCapacity = 1_000;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public SimulationMode getMode() {
		return mode;
	}

	public void setMode(SimulationMode mode) {
		this.mode = mode;
	}

	public int getMaxAccounts() {
		return maxAccounts;
	}

	public void setMaxAccounts(int maxAccounts) {
		this.maxAccounts = maxAccounts;
	}

	public String getEmailSuffix() {
		return emailSuffix;
	}

	public void setEmailSuffix(String emailSuffix) {
		this.emailSuffix = emailSuffix == null
				? null
				: emailSuffix.trim().toLowerCase(Locale.ROOT);
	}

	public String getExpectedPostgresqlDatabase() {
		return expectedPostgresqlDatabase;
	}

	public void setExpectedPostgresqlDatabase(String expectedPostgresqlDatabase) {
		this.expectedPostgresqlDatabase = expectedPostgresqlDatabase == null
				? null
				: expectedPostgresqlDatabase.trim();
	}

	public boolean isDestructiveResetAcknowledged() {
		return destructiveResetAcknowledged;
	}

	public void setDestructiveResetAcknowledged(boolean destructiveResetAcknowledged) {
		this.destructiveResetAcknowledged = destructiveResetAcknowledged;
	}

	/** Only the destructive fresh rebuild requires the explicit acknowledgement. */
	@AssertTrue(message = "app.simulation.destructive-reset-acknowledged must be true in FRESH mode")
	public boolean isDestructiveResetContractSatisfied() {
		return mode != SimulationMode.FRESH || destructiveResetAcknowledged;
	}

	/** PAUSE is intentionally inspectable without putting a shared password in the IDE config. */
	@AssertTrue(message = "app.simulation.common-password must be between 12 and 72 characters outside PAUSE mode")
	public boolean isCommonPasswordContractSatisfied() {
		if (mode == SimulationMode.PAUSE) return true;
		return commonPassword != null
				&& !commonPassword.isBlank()
				&& commonPassword.length() >= 12
				&& commonPassword.length() <= 72;
	}

	/** Never include this value in simulation manifests, logs, or reports. */
	public String getCommonPassword() {
		return commonPassword;
	}

	public void setCommonPassword(String commonPassword) {
		this.commonPassword = commonPassword;
	}

	public Path getReportDirectory() {
		return reportDirectory;
	}

	public void setReportDirectory(Path reportDirectory) {
		this.reportDirectory = reportDirectory;
	}

	public int getMailCaptureCapacity() {
		return mailCaptureCapacity;
	}

	public void setMailCaptureCapacity(int mailCaptureCapacity) {
		this.mailCaptureCapacity = mailCaptureCapacity;
	}
}
