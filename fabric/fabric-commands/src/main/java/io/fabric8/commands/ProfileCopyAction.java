/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.commands;

import io.fabric8.api.FabricService;
import io.fabric8.api.Profiles;
import io.fabric8.api.Version;
import io.fabric8.api.ProfileService;
import io.fabric8.utils.FabricValidations;

import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.CompleterValues;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;

@Command(name = ProfileCopy.FUNCTION_VALUE, scope = ProfileCopy.SCOPE_VALUE, description = ProfileCopy.DESCRIPTION)
public class ProfileCopyAction extends AbstractAction {

    @Option(name = "--version", description = "The profile version to copy. Defaults to the current default version.")
    private String versionParam;

    @Option(name = "-f", aliases = "--force", description = "Flag to allow overwriting the target profile (if exists).")
    private boolean force;

    @Argument(index = 0, required = true, name = "source profile", description = "Name of the source profile.")
    @CompleterValues(index = 0)
    private String source;

    @Argument(index = 1, required = true, name = "target profile", description = "Name of the target profile.")
    @CompleterValues(index = 1)
    private String target;

    private final ProfileService profileService;
    private final FabricService fabricService;

    ProfileCopyAction(FabricService fabricService) {
        this.profileService = fabricService.adapt(ProfileService.class);
        this.fabricService = fabricService;
    }

    @Override
    protected Object doExecute() throws Exception {
        FabricValidations.validateProfileName(source);
        FabricValidations.validateProfileName(target);
        Version version = versionParam != null ? profileService.getRequiredVersion(versionParam) : fabricService.getRequiredDefaultVersion();
        String versionId = version.getId();

        if (!version.hasProfile(source)) {
            System.out.println("Source profile " + source + " not found.");
            return null;
        } else if (version.hasProfile(target)) {
            if (!force) {
                System.out.println("Target profile " + target + " already exists. Use --force if you want to overwrite.");
                return null;
            }
        }

        Profiles.copyProfile(fabricService, versionId, source, target, force);
        return null;
    }

}
