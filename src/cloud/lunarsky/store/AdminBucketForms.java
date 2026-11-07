package cloud.lunarsky.store;

final class AdminBucketForms {
    private AdminBucketForms() { }

    static String create(String csrf) {
        StringBuilder html = new StringBuilder();
        html.append("<biscuit-dialogview id=create-bucket-dialog theme=deep-dark label='Create bucket'>")
            .append("<form class='settings-form dialog-form bucket-wizard-form' method=post action='")
            .append(AdminConsole.PATH)
            .append("/action/create-bucket'><input type=hidden name=csrf value='").append(csrf)
            .append("'><input type=hidden name=setup value=wizard>")
            .append("<biscuit-formwizardview id=create-bucket-wizard theme=deep-dark>")
            .append("<biscuit-formstep label=Name value=name>")
            .append("<label for=new-bucket>Bucket name</label>")
            .append("<input id=new-bucket name=bucket required minlength=3 maxlength=63 ")
            .append("pattern='[a-z0-9][a-z0-9-]*[a-z0-9]' placeholder='e.g. media-archive'>")
            .append("<small>Bucket URLs and presigned links use this name.</small>")
            .append("</biscuit-formstep><biscuit-formstep label=Storage value=storage>")
            .append("<label for=new-versioning>Versioning</label>")
            .append("<select id=new-versioning name=versioning><option value=off>Off</option>")
            .append("<option value=enabled>Enabled · retain old versions</option></select>")
            .append("<label for=new-ownership>Object ownership</label>")
            .append("<select id=new-ownership name=ownership>")
            .append("<option value=BucketOwnerEnforced>Bucket owner enforced · ACLs disabled</option>")
            .append("<option value=BucketOwnerPreferred>Bucket owner preferred</option>")
            .append("<option value=ObjectWriter>Object writer</option></select>")
            .append("</biscuit-formstep><biscuit-formstep label=Access value=access>")
            .append("<p>Block public access by default. Turn off only the controls you need.</p>")
            .append("<div class=check-grid>")
            .append("<label class=check><input type=checkbox name=blockPublicAcls checked>Block public ACLs</label>")
            .append("<label class=check><input type=checkbox name=ignorePublicAcls checked>Ignore public ACLs</label>")
            .append("<label class=check><input type=checkbox name=blockPublicPolicy checked>Block public policies</label>")
            .append("<label class=check><input type=checkbox name=restrictPublicBuckets checked>Restrict public buckets</label>")
            .append("</div><label class=check id=public-ack-row hidden>")
            .append("<input type=checkbox name=acknowledgePublic value=yes>")
            .append("I understand that relaxing these controls can expose data</label>")
            .append("</biscuit-formstep><biscuit-formstep label=Review value=review>")
            .append("<p id=bucket-wizard-review role=status></p>")
            .append("<small>Settings can be changed later in the bucket panel.</small>")
            .append("</biscuit-formstep></biscuit-formwizardview>")
            .append("<div class=dialog-actions><button type=button data-close-dialog>Cancel</button>")
            .append("</div></form></biscuit-dialogview>");
        return html.toString();
    }

    static void settings(StringBuilder html, ObjectStorage.Bucket bucket, String csrf,
                                 AdminBrowser.EditorDraft policyDraft) {
        String name = bucket.name();
        html.append("<section class=buckets><h2>Manage bucket</h2>")
            .append("<p>Changes take effect immediately. Type the bucket name to confirm access changes.</p>")
            .append("<div class=settings-grid><form class=settings-form method=post action='")
            .append(AdminConsole.PATH).append("/action/versioning'>").append(hidden(csrf, name))
            .append("<h3>Versioning</h3><select name=state>")
            .append(option("ENABLED", bucket.versioning() == ObjectStorage.VersioningState.ENABLED))
            .append(option("SUSPENDED", bucket.versioning() == ObjectStorage.VersioningState.SUSPENDED))
            .append("</select><button>Save versioning</button></form>")
            .append("<form class=settings-form method=post action='").append(AdminConsole.PATH)
            .append("/action/ownership'>").append(hidden(csrf, name))
            .append("<h3>Object Ownership</h3><select name=ownership>")
            .append(option("BucketOwnerEnforced", bucket.ownership() ==
                ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED))
            .append(option("BucketOwnerPreferred", bucket.ownership() ==
                ObjectStorage.ObjectOwnership.BUCKET_OWNER_PREFERRED))
            .append(option("ObjectWriter", bucket.ownership() ==
                ObjectStorage.ObjectOwnership.OBJECT_WRITER))
            .append("</select><input name=confirm placeholder='Type bucket name' required>")
            .append("<button>Save ownership</button></form></div>")
            .append("<form class=settings-form method=post action='").append(AdminConsole.PATH)
            .append("/action/public-access'>").append(hidden(csrf, name))
            .append("<h3>Block Public Access</h3><div class=check-grid>")
            .append(checkbox("blockPublicAcls", "Block new public ACLs", bucket.publicAccessBlock().blockPublicAcls()))
            .append(checkbox("ignorePublicAcls", "Ignore existing public ACLs", bucket.publicAccessBlock().ignorePublicAcls()))
            .append(checkbox("blockPublicPolicy", "Block public policies", bucket.publicAccessBlock().blockPublicPolicy()))
            .append(checkbox("restrictPublicBuckets", "Restrict public buckets", bucket.publicAccessBlock().restrictPublicBuckets()))
            .append("</div><input name=confirm placeholder='Type bucket name if reducing protection'>")
            .append("<button>Save public access</button></form>")
            .append("<form class='settings-form code-form' id=policy-editor method=post action='")
            .append(AdminConsole.PATH)
            .append("/action/policy'>").append(hidden(csrf, name))
            .append("<h3>Bucket policy</h3><p class=muted>Edit the policy JSON for this bucket.</p>")
            .append("<biscuit-codeview class=policy-editor id=policy-code language=json ")
            .append("aria-label='Bucket policy JSON' value='")
            .append(AdminConsole.escape(policyDraft == null ?
                bucket.policy() == null ? "" : bucket.policy() : policyDraft.value()))
            .append("'></biscuit-codeview><textarea class='code-fallback policy-fallback' ")
            .append("name=policy rows=9 spellcheck=false placeholder='No policy configured'>")
            .append(AdminConsole.escape(policyDraft == null ?
                bucket.policy() == null ? "" : bucket.policy() : policyDraft.value()))
            .append("</textarea><p class=editor-diagnostics data-editor=policy-code data-state=")
            .append(policyDraft == null ? "hint" : "error")
            .append(" role=status>")
            .append(AdminConsole.escape(policyDraft == null ? "JSON syntax is checked before saving." :
                policyDraft.error()))
            .append("</p><input name=confirm placeholder='Type bucket name for public policy'>")
            .append("<button>Save policy</button></form>");
        if (bucket.ownership() != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            grantForm(html, "bucket-grant", name, null, null, csrf);
        else html.append("<p class=muted>ACL editing is disabled while Bucket owner enforced is active.</p>");
        html.append("<biscuit-accordionview theme=deep-dark class=bucket-renamer>")
            .append("<biscuit-accordionitem label='Rename bucket'>")
            .append("<form class='settings-form rename-form' method=post action='")
            .append(AdminConsole.PATH).append("/action/rename-bucket'>")
            .append(hidden(csrf, name))
            .append("<p class=rename-warning role=note><strong>Renaming changes the bucket URL.</strong> ")
            .append("Clients using the old name or presigned links will stop working. Update application ")
            .append("configuration and any external access policies. Stored objects, versions, and ")
            .append("multipart uploads remain in this bucket. Scoped access keys must be moved first. ")
            .append("If S3_BUCKET names this bucket, update it before restarting the service.</p>")
            .append("<label for=rename-target>New bucket name</label>")
            .append("<input id=rename-target name=newName required minlength=3 maxlength=63 ")
            .append("pattern='[a-z0-9][a-z0-9-]*[a-z0-9]' placeholder='New name'>")
            .append("<label for=rename-confirm>Type the current name to confirm</label>")
            .append("<input id=rename-confirm name=confirm required autocomplete=off>")
            .append("<button type=submit>Rename bucket</button></form>")
            .append("</biscuit-accordionitem></biscuit-accordionview>");
        html.append("<form class='settings-form danger-zone' method=post action='")
            .append(AdminConsole.PATH).append("/action/delete-bucket'>")
            .append(hidden(csrf, name)).append("<h3>Delete bucket</h3>")
            .append("<p>Only an empty bucket can be deleted. Type its name to confirm.</p>")
            .append("<div class=form-row><input name=confirm required placeholder='Type bucket name'>")
            .append("<button>Delete bucket</button></div></form></section>");
    }

    static void grantForm(StringBuilder html, String action, String bucket,
                                  String key, String version, String csrf) {
        html.append("<form class=settings-form method=post action='").append(AdminConsole.PATH)
            .append("/action/").append(action).append("'>").append(hidden(csrf, bucket));
        if (key != null) html.append("<input type=hidden name=key value='")
            .append(AdminConsole.escape(key)).append("'>");
        if (version != null) html.append("<input type=hidden name=version value='")
            .append(AdminConsole.escape(version)).append("'>");
        html.append("<h3>ACL grants</h3><p class=muted>Use an access key ID, AllUsers, or AuthenticatedUsers.</p>")
            .append("<div class=form-row><input name=principal required placeholder='Grantee'>")
            .append("<select name=permission><option>READ</option><option>WRITE</option>")
            .append("<option>READ_ACP</option><option>WRITE_ACP</option>")
            .append("<option>FULL_CONTROL</option></select>")
            .append("<select name=operation><option value=add>Add grant</option>")
            .append("<option value=remove>Remove grantee</option></select>")
            .append("<input name=confirm placeholder='Bucket name for public grant'>")
            .append("<button>Save grant</button></div></form>");
    }

    static String hidden(String csrf, String bucket) {
        return "<input type=hidden name=csrf value='" + csrf +
            "'><input type=hidden name=bucket value='" + AdminConsole.escape(bucket) + "'>";
    }

    private static String option(String name, boolean selected) {
        return "<option" + (selected ? " selected" : "") + ">" + name + "</option>";
    }

    private static String checkbox(String name, String label, boolean checked) {
        return "<label class=check><input type=checkbox name='" + name + "'" +
            (checked ? " checked" : "") + ">" + label + "</label>";
    }

}
