/*
 * #%L
 * Alfresco Records Management Module
 * %%
 * Copyright (C) 2005 - 2017 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * -
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 * -
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * -
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * -
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.alfresco.module.org_alfresco_module_rm.model.rma.type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.alfresco.model.ContentModel;
import org.alfresco.module.org_alfresco_module_rm.model.BaseBehaviourBean;
import org.alfresco.module.org_alfresco_module_rm.recordfolder.RecordFolderService;
import org.alfresco.module.org_alfresco_module_rm.security.FilePlanPermissionService;
import org.alfresco.module.org_alfresco_module_rm.vital.VitalRecordService;
import org.alfresco.repo.copy.CopyBehaviourCallback;
import org.alfresco.repo.copy.CopyDetails;
import org.alfresco.repo.copy.DefaultCopyBehaviourCallback;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.policy.Behaviour.NotificationFrequency;
import org.alfresco.repo.policy.annotation.Behaviour;
import org.alfresco.repo.policy.annotation.BehaviourBean;
import org.alfresco.repo.policy.annotation.BehaviourKind;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;

/**
 * rma:recordCategory behaviour bean
 *
 * @author Roy Wetherall
 * @since 2.2
 */
@BehaviourBean
(
   defaultType = "rma:recordCategory"
)
public class RecordCategoryType extends    BaseBehaviourBean
                                implements NodeServicePolicies.OnCreateChildAssociationPolicy,
                                           NodeServicePolicies.OnCreateNodePolicy
{
    private final static List<QName> ACCEPTED_UNIQUE_CHILD_TYPES = new ArrayList<QName>();
    private final static List<QName> ACCEPTED_NON_UNIQUE_CHILD_TYPES = Arrays.asList(TYPE_RECORD_CATEGORY, TYPE_RECORD_FOLDER);

    /** vital record service */
    protected VitalRecordService vitalRecordService;

    /** file plan permission service */
    protected FilePlanPermissionService filePlanPermissionService;

    /** record folder service */
    private RecordFolderService recordFolderService;

    /**
     * @param vitalRecordService    vital record service
     */
    public void setVitalRecordService(VitalRecordService vitalRecordService)
    {
        this.vitalRecordService = vitalRecordService;
    }

    /**
     * @param filePlanPermissionService file plan permission service
     */
    public void setFilePlanPermissionService(FilePlanPermissionService filePlanPermissionService)
    {
        this.filePlanPermissionService = filePlanPermissionService;
    }

    /**
     * @param recordFolderService   record folder service
     */
    public void setRecordFolderService(RecordFolderService recordFolderService)
    {
        this.recordFolderService = recordFolderService;
    }

    /**
     * On every event
     *
     * @see org.alfresco.repo.node.NodeServicePolicies.OnCreateChildAssociationPolicy#onCreateChildAssociation(org.alfresco.service.cmr.repository.ChildAssociationRef, boolean)
     */
    @Override
    @Behaviour
    (
       kind = BehaviourKind.ASSOCIATION
    )
    public void onCreateChildAssociation(ChildAssociationRef childAssocRef, boolean bNew)
    {
        QName childType = nodeService.getType(childAssocRef.getChildRef());

        // We need to automatically cast the created folder to record folder if it is a plain folder
        // This occurs if the RM folder has been created via IMap, WebDav, etc. Don't check subtypes.
        // Some modules use hidden folders to store information (see RM-3283).
        if (childType.equals(ContentModel.TYPE_FOLDER))
        {
            nodeService.setType(childAssocRef.getChildRef(), TYPE_RECORD_FOLDER);
        }

        validateNewChildAssociation(childAssocRef.getParentRef(), childAssocRef.getChildRef(), ACCEPTED_UNIQUE_CHILD_TYPES, ACCEPTED_NON_UNIQUE_CHILD_TYPES);

        if (bNew)
        {
            // setup the record folder
            recordFolderService.setupRecordFolder(childAssocRef.getChildRef());
        }
    }

    /**
     * On transaction commit
     *
     * @see org.alfresco.repo.node.NodeServicePolicies.OnCreateChildAssociationPolicy#onCreateChildAssociation(org.alfresco.service.cmr.repository.ChildAssociationRef, boolean)
     */
    @Behaviour
    (
       kind = BehaviourKind.ASSOCIATION,
       policy = "alf:onCreateChildAssociation",
       notificationFrequency = NotificationFrequency.TRANSACTION_COMMIT
    )
    public void onCreateChildAssociationOnCommit(ChildAssociationRef childAssocRef, final boolean bNew)
    {
        final NodeRef child = childAssocRef.getChildRef();

        behaviourFilter.disableBehaviour();
        try
        {
            AuthenticationUtil.runAsSystem(new RunAsWork<Void>()
            {
                @Override
                public Void doWork()
                {
                    // setup vital record definition
                    vitalRecordService.setupVitalRecordDefinition(child);

                    return null;
                }
            });
        }
        finally
        {
            behaviourFilter.enableBehaviour();
        }
    }

    /**
     * @see org.alfresco.repo.node.NodeServicePolicies.OnCreateNodePolicy#onCreateNode(org.alfresco.service.cmr.repository.ChildAssociationRef)
     */
    @Override
    @Behaviour
    (
       kind = BehaviourKind.CLASS,
       notificationFrequency = NotificationFrequency.TRANSACTION_COMMIT
    )
    public void onCreateNode(final ChildAssociationRef childAssocRef)
    {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("rma:recordCategory|alf:onCreateNode|this.onCreateNode()|TRANSATION_COMMIT");
        }

        // execute behaviour code as system user
        AuthenticationUtil.runAsSystem(new RunAsWork<Void>()
        {
            @Override
            public Void doWork()
            {
                // setup record category permissions
                filePlanPermissionService.setupRecordCategoryPermissions(childAssocRef.getChildRef());

                return null;
            }
        });

    }

    /**
     * Copy callback for record category
     */
    @Behaviour
    (
        kind = BehaviourKind.CLASS,
        policy = "alf:getCopyCallback"
    )
    public CopyBehaviourCallback onCopyRecordCategory(final QName classRef, final CopyDetails copyDetails)
    {
        return new DefaultCopyBehaviourCallback()
        {
            /**
             * If the targets parent is a Record Folder -- Do Not Allow Copy
             *
             * @param classQName
             * @param copyDetails
             * @return boolean
             */
            @Override
            public boolean getMustCopy(QName classQName, CopyDetails copyDetails)
            {
                return nodeService.getType(copyDetails.getTargetParentNodeRef()).equals(TYPE_RECORD_FOLDER) ? false : true;
            }
        };
    }
}
